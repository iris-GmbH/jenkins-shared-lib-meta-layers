// SPDX-License-Identifier: MIT
// Copyright (C) 2021 iris-GmbH infrared & intelligent sensors

def call() {
    pipeline {
        agent any
        options {
            disableConcurrentBuilds()
            parallelsAlwaysFailFast()
        }
        environment {
            // S3 bucket for temporary artifacts
            S3_BUCKET = 'iris-devops-tempartifacts-693612562064'
            SDK_IMAGE = 'irma6-maintenance'
        }
        stages {
            stage('Preparation Stage') {
                steps {
                    // clean workspace
                    cleanWs disableDeferredWipeout: true, deleteDirs: true
                    // checkout iris-kas repo
                    checkout([$class: 'GitSCM',
                        branches: [[name: '*/develop']],
                        extensions: [[$class: 'CloneOption',
                        noTags: false,
                        reference: '',
                        shallow: false]],
                        userRemoteConfigs: [[url: 'https://github.com/iris-GmbH/iris-kas.git']]])
                    script {
                        // if this is a PR branch, the env variable "CHANGE_BRANCH" will contain the real branch name, which we need for checkout later on
                        env.REAL_GIT_BRANCH = sh(script: "if [ -n \"${CHANGE_BRANCH}\" ]; then echo \"${CHANGE_BRANCH}\"; else echo \"${GIT_BRANCH}\"; fi", returnStdout: true).trim()
                    }
                    // try to checkout identical named branch, do not checkout master
                    sh "if [ \"\$(basename ${REAL_GIT_BRANCH})\" != \"master\" ]; then git checkout ${REAL_GIT_BRANCH} || true; fi"
                    // manually upload kas sources to S3, as to prevent upload conflicts in parallel steps
                    zip dir: '', zipFile: 'iris-kas-sources.zip'
                    s3Upload acl: 'Private',
                        bucket: "${S3_BUCKET}",
                        file: 'iris-kas-sources.zip',
                        path: "${JOB_NAME}/${GIT_COMMIT}/iris-kas-sources.zip",
                        payloadSigningEnabled: true,
                        sseAlgorithm: 'AES256'
                    sh 'printenv | sort'
                }
            }
            
            stage('Build Firmware') {
                matrix {
                    axes {
                        axis {
                            name 'MULTI_CONF'
                            values 'sc573-gen6', 'imx8mp-evk'
                        }
                        axis {
                            name 'IMAGES'
                            values 'irma6-deploy irma6-maintenance irma6-dev'
                        }
                    }
                    stages {
                        stage("Build Firmware Artifacts") {
                            steps {
                                awsCodeBuild buildSpecFile: 'buildspecs/build_firmware_images_develop.yml',
                                    projectName: 'iris-devops-kas-large-amd-codebuild',
                                    credentialsType: 'keys',
                                    downloadArtifacts: 'false',
                                    region: 'eu-central-1',
                                    sourceControlType: 'project',
                                    sourceTypeOverride: 'S3',
                                    sourceLocationOverride: "${S3_BUCKET}/${JOB_NAME}/${GIT_COMMIT}/iris-kas-sources.zip",
                                    envVariables: "[ { MULTI_CONF, $MULTI_CONF }, { IMAGES, $IMAGES }, { GIT_BRANCH, $REAL_GIT_BRANCH }, { SDK_IMAGE, $SDK_IMAGE }, { HOME, /home/builder }, { JOB_NAME, $JOB_NAME } ]"
                            }
                        }
                    }
                }
            }
        }
    
        post {
            // clean after build
            always {
                cleanWs(cleanWhenNotBuilt: false,
                        deleteDirs: true,
                        disableDeferredWipeout: true,
                        notFailBuild: true)
            }
        }
    }
}
