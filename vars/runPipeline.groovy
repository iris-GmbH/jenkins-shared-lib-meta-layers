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
                    script {
                        // if this is a PR branch, the env variable "CHANGE_BRANCH" will contain the real branch name, which we need for checkout later on
                        if (env.CHANGE_BRANCH) {
                            env.REAL_GIT_BRANCH = env.CHANGE_BRANCH
                        }
                        else {
                            env.REAL_GIT_BRANCH = env.GIT_BRANCH
                        }
                    }
                    // clean workspace
                    cleanWs disableDeferredWipeout: true, deleteDirs: true
                    // checkout iris-kas repo
                    checkout([$class: 'GitSCM',
                        branches: [[name: '*/develop']],
                        extensions: [[$class: 'CloneOption',
                        reference: '',
                        shallow: false]],
                        userRemoteConfigs: [[url: 'https://github.com/iris-GmbH/iris-kas.git']]])
                    // try to checkout identical named branch feature/bugfix/... branches
                    sh """
                        if [ \"\$(basename ${REAL_GIT_BRANCH})\" != \"develop\" ] && [ \"\$(basename ${REAL_GIT_BRANCH})\" != \"master\" ] && \$(echo \"${REAL_GIT_BRANCH}\" | grep -vqE 'release/.*'); then
                            git checkout ${REAL_GIT_BRANCH} || true;
                        fi
                    """
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
            
            stage('Build Firmware Artifacts') {
                script {
                    runDevelopBuild()
                }
            }

            stage('Run QEMU Tests') {
                script {
                    runQemuTests()
                }
            }
        }

        post {
            // clean after build
            cleanup {
                cleanWs(cleanWhenNotBuilt: false,
                        deleteDirs: true,
                        disableDeferredWipeout: true,
                        notFailBuild: true)
            }
        }
    }
}
