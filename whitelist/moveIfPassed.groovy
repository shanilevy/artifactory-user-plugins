/*
 * Copyright (C) 2014 JFrog Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.artifactory.exception.CancelException
import org.artifactory.fs.StatsInfo
import org.artifactory.md.Properties
import org.artifactory.repo.RepoPath
import org.artifactory.repo.RepoPathFactory
import org.artifactory.common.StatusHolder

/**
 * Example REST call(s):
 */

executions {
    moveIfPassed() { params ->
        def filePattern = params['filePattern'] ? params['filePattern'][0] as String : '*'
        def srcRepo = params['srcRepo'] ? params['srcRepo'][0] as String : 'generic-xx'
        def archiveRepo = params['archiveRepo'] ? params['archiveRepo'][0] as String : 'generic-xx-archived'
        def includePropertySet = params['includePropertySet'] ? params['includePropertySet'][0] as String : ''
        def archiveProperty = params['archiveProperty'] ? params['archiveProperty'][0] as String : 'verified.timestamp'


        moveScannedArtifacts(
                log,
                filePattern,
                srcRepo,
                archiveRepo,
                includePropertySet,
                archiveProperty)
    }
}

/**
 */

private moveScannedArtifacts(
        log,
        filePattern,
        srcRepo,
        archiveRepo,
        includePropertySet,
        archiveProperty) {
    log.warn('Starting archive process for old artifacts ...')
    log.info('File match pattern: {}', filePattern)
    log.info('Source repository: {}', srcRepo)
    log.info('Archive repository: {}', archiveRepo)
    log.info('Include property set: {}', includePropertySet)
    log.info('Archive property: {}', archiveProperty)
    //log.info('Number of artifacts to keep per directory: {}', numKeepArtifacts)

    // Abort if no selection criteria was sent in (we don't want to archive everything blindly)
    if (includePropertySet == '') {
        log.error('No selection criteria specified, exiting now!')
        throw new CancelException('No selection criteria specified!', 400)
    }

    // Booleans verifying whether or not to archive the artifact
    boolean archiveIncludeProperties = true
    // Total count of artifacts that were archived
    int artifactsArchived = 0

    artifactsMove =
            searches.artifactsByName(filePattern, srcRepo).each { artifact ->
                log.info('Search found artifact: {}', artifact)

                // Get times
                def todayDate = new Date()
                def todayTime = todayDate.time
                log.info("Today's date: {}", todayDate)

                // Check if we are to include some artifacts based on attributes
                if (includePropertySet != '') {
                    log.info('We are going to include artifacts based on attributes...')
                    Map<String, String> includeMap = translatePropertiesString(includePropertySet)

                    log.info('about to call verify properties for true')
                    // Call the function to check if we need to archive based on included properties
                    archiveIncludeProperties = verifyProperties(artifact, includeMap, true)
                }

                // Logging
                log.debug('-- archiveIncludeProperties: {}', archiveIncludeProperties)

                // Check if we want to archive the item
                // TODO: Are we worried about hitting exclusion properties as well as inclusion properties
                //       on the same artifact? Does this need to be handled?
                if (archiveIncludeProperties) {
                    def boolean keepArtifact = false


                    // One last check to make sure we actually want to archive the artifact
                    if (!keepArtifact) {
                        log.warn('About to archive artifact: {}', artifact)

                        // Get the properties from the existing artifact
                        Properties properties = repositories.getProperties(artifact)

                        // Deploy over the existing artifact with a 1-byte file
                        byte[] buf = new byte[1]
                        StatusHolder status = repositories.deploy(artifact, new ByteArrayInputStream(buf))
                        log.debug('Status of deploy: {}', status)
                        if (status.isError()) {
                            log.error('Call to deploy artifact {} failed!', artifact)
                        }


                        // Add all of the properties back to the artifact
                        properties.keys().each { key ->
                            Set<String> values = properties.get(key)
                            log.debug('Adding key: {}, values: {} to re-deployed artifact', key, values)
                            repositories.setProperty(artifact, key, *(values as List))
                        }

                        // Call the function to move the artifact
                        moveBuildArtifact(archiveRepo, artifact, archiveProperty, todayTime)

                        artifactsArchived++
                    }
                } else {
                    log.info('Not archiving artifact: {}', artifact)
                    log.debug('Include properties policy status: {}', archiveIncludeProperties)
                }
            }

    log.warn('Process found {} total artifact(s)', artifactsMove.size)
    log.warn('Process archived {} total artifact(s)', artifactsArchived)
}

// Function to move the build artifact and set a property for the time it was moved
def moveBuildArtifact(archiveRepo, RepoPath artifact, String property, time) {
    // Get the translated file path for the new repo
    def translatedFilePath = repositories.translateFilePath(artifact, archiveRepo)
    log.debug('translatedFilePath: {}', translatedFilePath)

    // Get the path for the repo to move the artifact to
    def archiveRepoPath = RepoPathFactory.create(archiveRepo, translatedFilePath)
    log.debug('archiveRepoPath: {}', archiveRepoPath)

    // Move the actual artifact and check that it worked
    StatusHolder status = repositories.move(artifact, archiveRepoPath)
    log.debug('status of move: {}', status)
    if (status.isError()) {
        log.error('Call to move artifact {} failed!', artifact)
    }

    // Tag the artifact as being archived (set a new property)
    def properties = repositories.setProperty(archiveRepoPath, property, String.valueOf(time))
    log.debug('Artifact {} properties: {}', archiveRepoPath, properties)
}



// Function to take in a string representation of properties and return the map of it
Map<String, String> translatePropertiesString(String properties) {
    // Verify the properties string
    if (properties ==~ /(\w.+)(:\w.)*(;(\w.+)(:\w.)*)*/) {
        log.debug('Properties are of the proper format! Properties: {}', properties)
    } else {
        log.error('Properties are not of the proper format: {}. Exiting now!', properties)
        // Throw an exception due to the wrong input
        throw new CancelException('Incorrect format for properties!', 400)
    }

    // The map to be filled in
    Map<String, String> map = new HashMap()

    // Split the string by ';'
    String[] propertySets = properties.tokenize(';')

    // Iterate over the property sets
    propertySets.each {
        log.debug('propertySet: {}', it)
        // Split the key and value by ':'
        def (key, value) = it.tokenize(':')
        log.debug('key: {}, value: {}', key, value)
        // Add the set to the map
        map.put(key, value)
    }

    map
}

// Function to check an artifact against a property set
boolean verifyProperties(artifact, Map<String, String> propertyMap, boolean inclusive) {
    log.debug('verify properties called with inclusive: {}', inclusive)

    // Get the properties for the artifact
    Properties properties = repositories.getProperties(artifact)
    log.debug('Got properties for artifact: {}', properties)

    // Iterate over the propertySet we are verifying the artifact against
    for (String key : propertyMap.keySet()) {
        log.debug('iteration --> key: {}', key)

        // Check if the artifact has the property
        if (repositories.hasProperty(artifact, key)) {
            // Get the value we need to check for
            value = propertyMap.get(key)
            log.debug('value we are attempting to match: {}, for key: {}', value, key)

            // Check if we were even given a value to match on the key
            if (value != null) {
                // Check if the artifact contains the value for the key
                valueSet = repositories.getPropertyValues(artifact, key)
                log.debug('value set: {}, size: {}', valueSet, valueSet.size())
                if (valueSet.contains(value)) {
                    log.debug('Both have key: {}, value: {}', key, value)
                } else {
                    log.debug('Both have key: {}, but values differ. Value checked: {}', key, value)
                    return !inclusive
                }
            } else {
                log.debug('We were not given a value for the provided key: {}, this is a match since the key matches.', key)
            }
        } else {
            log.debug('The artifact did not contain the key: {}, failure to match properties', key)
            return !inclusive
        }
    }

    // Return true or false depending on include/exclude logic
    inclusive
}
