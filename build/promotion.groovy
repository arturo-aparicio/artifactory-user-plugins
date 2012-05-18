/*
 * Artifactory is a binaries repository manager.
 * Copyright (C) 2012 JFrog Ltd.
 *
 * Artifactory is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Artifactory is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Artifactory.  If not, see <http://www.gnu.org/licenses/>.
 */

import groovy.transform.Field
import groovy.xml.StreamingMarkupBuilder
import org.artifactory.build.Artifact
import org.artifactory.build.BuildRun
import org.artifactory.build.Dependency
import org.artifactory.build.DetailedBuildRun
import org.artifactory.build.Module
import org.artifactory.build.ReleaseStatus
import org.artifactory.common.StatusHolder
import org.artifactory.exception.CancelException
import org.artifactory.fs.FileInfo
import org.artifactory.repo.RepoPath
import org.artifactory.util.StringInputStream

import static groovy.xml.XmlUtil.serialize
import static org.artifactory.repo.RepoPathFactory.create

promotions {
    /**
     * A REST executable build promotion definition.
     *
     * Context variables:
     * status (int) - a response status code. Defaults to -1 (unset).
     * message (java.lang.String) - a text message to return in the response body, replacing the response content. Defaults to null.
     *
     * Plugin info annotation parameters:
     * version (java.lang.String) - Closure version. Optional.
     * description (java.lang.String - Closure description. Optional.
     * params (java.util.Map<java.lang.String, java.lang.String>) - Closure parameters. Optional.
     * users (java.util.Set<java.lang.String>) - Users permitted to query this plugin for information or invoke it.
     * groups (java.util.Set<java.lang.String>) - Groups permitted to query this plugin for information or invoke it.
     *
     * Closure parameters:
     * buildName (java.lang.String) - The build name specified in the REST request.
     * buildNumber (java.lang.String) - The build number specified in the REST request.
     * params (java.util.Map<java.lang.String, java.util.List<java.lang.String>>) - The parameters specified in the REST request.
     */
    snapshotToRelease(users: "jenkins", params: [snapExp: 'd14', targetRepository: 'gradle-release-local']) { buildName, buildNumber, params ->
        log.info 'Promoting build: ' + buildName + '/' + buildNumber

        //1. Extract properties
        buildStartTime = getStringProperty(params, 'buildStartTime', false)
        String snapExp = getStringProperty(params, 'snapExp', true)
        String targetRepository = getStringProperty(params, 'targetRepository', true)
        //2. Get Stage build information by name/number
        //Sanity check
        List<BuildRun> buildsRun = builds.getBuilds(buildName, buildNumber, buildStartTime)
        if (buildsRun.size() > 1) cancelPromotion('Found two matching build to promote, please provide build start time', null, 409)

        def buildRun = buildsRun[0]
        if (buildRun == null) cancelPromotion("Build $buildName/$buildNumber was not found, canceling promotion", null, 409)
        DetailedBuildRun stageBuild = builds.getDetailedBuild(buildRun)
        String releasedBuildNumber = "$stageBuild.number-r"
        Set<FileInfo> stageArtifactsList = builds.getArtifactFiles(buildRun)

        if (!builds.getBuilds(buildName, "$stageBuild.number-r", null).empty) {
            cancelPromotion("Build $buildName/$buildNumber was already promoted under build number$releasedBuildNumber", null, 400)
        }


        //3. Prepare release DetailedBuildRun and release artifacts for deployment
        @Field String timestamp = System.currentTimeMillis().toString()
        DetailedBuildRun releaseBuild = stageBuild.copy(releasedBuildNumber)
        releaseBuild.properties
        releaseArtifactsSet = [] as Set<RepoPath>
        List<Module> modules = releaseBuild.modules
        //Modify this condition to fit your needs
        if (!(snapExp == 'd14' || snapExp == 'SNAPSHOT')) cancelPromotion('This plugin logic support only Unique/Non-Unique snapshot patterns', null, 400)
        //If there is mor then one Artifacts that have the same checksum but different name only the first one will be return in the search so they will have to have different care
        def missingArtifacts = []
        //Iterate over modules list
        modules.each {Module module ->
            //Find project inner module dependencies
            List<FileInfo> innerModuleDependencies = []
            def dependenciesList = module.dependencies
            dependenciesList.each {dep ->
                FileInfo res = stageArtifactsList.asList().find {sal -> sal.checksumsInfo.sha1 == dep.sha1}
                if (res != null) innerModuleDependencies << res
            }

            //Find and set module ID with release version
            def id = module.id
            def moduleInfo = parseModuleId(id, snapExp)
            module.id = moduleInfo.id

            //Iterate over the artifact list, create a release artifact deploy it and add it to the release DetailedBuildRun
            //Save a copy of the RepoPath to roll back if needed
            List<Artifact> artifactsList = module.artifacts
            RepoPath releaseRepoPath = null
            try {
                artifactsList.eachWithIndex {art, index ->
                    def stageRepoPath = getStageRepoPath(art, stageArtifactsList)
                    if (stageRepoPath != null) {
                        releaseRepoPath = getReleaseRepoPath(targetRepository, stageRepoPath, moduleInfo.stageVersion, snapExp)
                    } else {
                        missingArtifacts << art
                        return
                    }

                    //If ivy.xml or pom then create and deploy a new Artifact with the fix revision,status,publication inside the xml
                    StatusHolder status
                    switch (art.type) {
                        case 'ivy':
                            status = generateAndDeployReleaseIvyFile(stageRepoPath, releaseRepoPath, innerModuleDependencies, snapExp)
                            break;
                        case 'pom':
                            status = generateAndDeployReleasePomFile(stageRepoPath, releaseRepoPath, innerModuleDependencies, stageArtifactsList, snapExp)
                            break;
                        default:
                            status = repositories.copy(stageRepoPath, releaseRepoPath)
                    }
                    if (status.isError()) rollback(releaseBuild, releaseArtifactsSet, status.exception)
                    setReleaseProperties(stageRepoPath, releaseRepoPath)
                    releasedArtifact = new Artifact(repositories.getFileInfo(releaseRepoPath), art.type)
                    artifactsList[index] = releasedArtifact

                    //Add the release RepoPath for roll back
                    releaseArtifactsSet << releaseRepoPath
                }
            } catch (IllegalStateException e) {
                rollback(releaseBuild, releaseArtifactsSet, e.message, e)
            }

        }

        //Fix dependencies of other modules with release version
        try {
            modules.each {mod ->
                releaseDependencies = []
                def dependenciesList = mod.dependencies
                dependenciesList.each {dep ->
                    def match = stageArtifactsList.asList().find {item ->
                        item.checksumsInfo.sha1 == dep.sha1
                    }
                    if (match != null) {
                        //interproject dependency, change it
                        //Until GAP-129 is resolved this will have todo
                        List<String> tokens = match.repoPath.path.split('/')
                        String stageVersion = tokens[tokens.size() - 2]
                        def releaseRepoPath = getReleaseRepoPath(targetRepository, match.repoPath, stageVersion, snapExp)
                        def releaseFileInfo = repositories.getFileInfo(releaseRepoPath)
                        def moduleInfo = parseModuleId(dep.id, snapExp)
                        releaseDependencies << new Dependency(moduleInfo.id, releaseFileInfo, dep.scopes, dep.type)
                    } else {
                        //external dependency, leave it
                        releaseDependencies << dep
                    }
                }
                dependenciesList.clear()
                dependenciesList.addAll(releaseDependencies)
            }
        } catch (IllegalStateException e) {
            rollback(releaseBuild, releaseArtifactsSet, e.message, e)
        }

        //Add release status
        def statuses = releaseBuild.releaseStatuses
        statuses << new ReleaseStatus("Released", 'Releasing build gradle-multi-example', targetRepository, getStringProperty(params, 'ciUser', false), security.currentUsername)
        //Save new DetailedBuildRun (Build info)
        builds.saveBuild(releaseBuild)
        if (releaseArtifactsSet.size() != stageArtifactsList.size()) {
            log.warn "The plugin implementaion don't fit your build, release artifact size is different from the staging number"
            rollback(releaseBuild, releaseArtifactsSet, null)
        }

        message = " Build $buildName/$buildNumber has been successfully promoted"
        log.info message
        status = 200
    }
}

private Map<String, String> parseModuleId(String id, String snapExp) {
    List idTokens = id.split(':')
    String stageVersion = idTokens.pop()
    //Implement version per module logic
    idTokens << extractVersion(stageVersion, snapExp)
    id = idTokens.join(':')
    [id: id, stageVersion: stageVersion]
}

private void rollback(BuildRun releaseBuild, Set<RepoPath> releaseArtifactsSet, String message = 'Rolling back build promotion', Throwable cause, int statusCode = 500) {
    releaseArtifactsSet.each {item ->
        StatusHolder status = repositories.delete(item)
        //now let's delete empty folders
        deletedItemParentDirRepoPath = item.parent
        while (!deletedItemParentDirRepoPath.root && repositories.getChildren(deletedItemParentDirRepoPath).empty) {
            repositories.delete(deletedItemParentDirRepoPath)
            deletedItemParentDirRepoPath = deletedItemParentDirRepoPath.parent
        }
        if (status.error) {
            log.error "Rollback failed! Failed to delete $item, error is $status.statusMsg", status.exception
        } else {
            log.info "$item deleted"
        }
    }
    StatusHolder status = builds.deleteBuild(releaseBuild)
    if (status.error) {
        log.error "Rollback failed! Failed to delete $releaseBuild, error is $status.statusMsg", status.exception
    }
    cancelPromotion(message, cause, statusCode)
}


private RepoPath getReleaseRepoPath(String targetRepository, RepoPath stageRepoPath, String stageVersion, String snapExp) {
    def layoutInfo = repositories.getLayoutInfo(stageRepoPath)
    releaseVersion = extractVersion(stageVersion, snapExp)
    String stagingPath = stageRepoPath.path
    if (layoutInfo.integration || stageVersion =~ ".*" + snapExp + ".*") {
        String releasePath
        //this might not work
        if (layoutInfo.valid) {
            releasePath = stagingPath.replace("-$layoutInfo.folderIntegrationRevision", '') //removes -SNAPSHOT from folder name
            releasePath = releasePath.replace("-$layoutInfo.fileIntegrationRevision", '') //removes -timestamp from file name
        } else {
            //let's hope the version is simple
            releasePath = stagingPath.replace(stageVersion, releaseVersion)
        }
        if (releasePath == stagingPath) {
            throw new IllegalStateException("Converting stage repository path$stagingPath to released repository path failed, please check your snapshot expression")
        }
        create(targetRepository, releasePath)
    } else {
        log.info "Your build contains release version of $stageRepoPath"
        create(targetRepository, stagingPath)
    }
}

def getStageRepoPath(Artifact stageArtifact, Set<FileInfo> stageArtifactsList) {
    //stageArtifact.name = multi-2.15-SNAPSHOT.pom
    //stageArtifactsList.toArray()[0].name= multi1-2.15-20120503.095917-1-tests.jar
    def tmpArtifact = stageArtifactsList.find {
        def layoutInfo = repositories.getLayoutInfo(it.repoPath)
        //this might not work for repos without layout
        //checking the name won't help - it is  called ivy.xml
        (stageArtifact.type == 'ivy' || !layoutInfo.valid || stageArtifact.name.startsWith(layoutInfo.module)) &&
                it.sha1 == stageArtifact.sha1
    }
    if (tmpArtifact == null) {
        log.warn "No Artifact with the same name and sha1 was found, somthing is wrong with your build info, look for $stageArtifact.name $stageArtifact.sha1 there is probably mor then one artifact with the same sha1"
        return null
    }
    tmpArtifact.repoPath
}

@SuppressWarnings("GroovyAccessibility") //it complains about Node.parent when I refer to <parent> tag
private StatusHolder generateAndDeployReleasePomFile(RepoPath stageRepoPath, releaseRepoPath, List<FileInfo> innerModuleDependencies, Set<FileInfo> stageArtifactsList, String snapExp) {
    def stagePom = repositories.getStringContent(stageRepoPath)
    def project = new XmlSlurper(false, false).parseText(stagePom)
    if (!project.version.isEmpty()) {
        project.version = extractVersion(project.version.text(), snapExp)
    }
    //also try the parent
    if (!project.parent.version.isEmpty()) {
        project.parent.version = extractVersion(project.parent.version.text(), snapExp)
    }

    innerModuleDependencies.each { FileInfo artifact ->
        def layoutInfo = repositories.getLayoutInfo(artifact.repoPath)
        project.dependencies.dependency.findAll {dependency ->
            dependency.groupId == layoutInfo.organization && dependency.artifactId == layoutInfo.module
        }.each {dependency ->
            dependency.version = extractVersion(dependency.version.isEmpty() ? "${layoutInfo.baseRevision}${layoutInfo.integration ? '-' + layoutInfo.folderIntegrationRevision : ''}" : dependency.version.text(), snapExp)
        }
    }

    repositories.deploy(releaseRepoPath, streamXml(project))

}

private StringInputStream streamXml(xml) {
    String result = new StreamingMarkupBuilder().bind { mkp.yield xml }
    new StringInputStream(serialize(result))
}

//Pars the xml and modify values and deploy
private StatusHolder generateAndDeployReleaseIvyFile(RepoPath stageRepoPath, releaseRepoPath, List<FileInfo> innerModuleDependencies, String snapExp) {
    def stageIvy = repositories.getStringContent(stageRepoPath)
    //stageIvy.replace('m:classifier','classifier')
    def slurper = new XmlSlurper(false, false)
    slurper.keepWhitespace = true
    def releaseIvy = slurper.parseText(stageIvy)
    def info = releaseIvy.info[0]
    def stageRev = info.@revision.text()
    info.@revision = extractVersion(stageRev, snapExp)
    info.@status = 'release'
    //fix date and xml alignment and module dependency
    info.@publication = timestamp
    //Fix inner module dependencies
    innerModuleDependencies.each {art ->
        String[] tokens = art.repoPath.path.split('/')
        def stageVersion = tokens[tokens.size() - 2]
        def name = art.name.split('-')[0]
        def org = tokens[0]
        releaseIvy.dependencies.dependency.findAll {md -> md.@org == org && md.@rev == stageVersion && md.@name == name }.each {e -> e.@rev = extractVersion(stageVersion, snapExp)}
    }

    repositories.deploy(releaseRepoPath, streamXml(releaseIvy))
}

//Copy properties and modify status/timestamp
private void setReleaseProperties(stageRepoPath, releaseRepoPath) {
    def properties = repositories.getProperties(stageRepoPath)
    properties.replaceValues('build.number', ["${properties.getFirst('build.number')}-r"])
    properties.replaceValues('build.status', ['release'])
    properties.replaceValues('build.timestamp', [timestamp])
    def keys = properties.keys()
    keys.each {item ->
        key = item
        def values = properties.get(item)
        values.each {val ->
            repositories.setProperty(releaseRepoPath, key, val)
        }
    }

}

//This is the place to implement the release version expressions logic
def extractVersion(String stageVersion, snapExp) {
    stageVersion.split('-')[0]
}

private String getStringProperty(params, pName, mandatory) {
    def key = params[pName]
    def val = key == null ? null : key[0].toString()
    if (mandatory && val == null) cancelPromotion("$pName is mandatory paramater", null, 400)
    return val
}

def cancelPromotion(String message, Throwable cause, int errorLevel) {
    log.warn message
    throw new CancelException(message, cause, errorLevel)
}