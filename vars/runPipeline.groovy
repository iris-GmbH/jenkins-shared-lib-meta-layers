// SPDX-License-Identifier: MIT
// Copyright (C) 2021 iris-GmbH infrared & intelligent sensors

@Library('jenkins-shared-lib-meta-devbuild-testing@master') _

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
                    // try to checkout identical named branch, do not checkout master or PR branch
                    sh """
                        if [ \"\$(basename ${GIT_BRANCH})\" != \"master\" ] && [ \"\$(echo ${GIT_BRANCH} | grep -vE '^PR-')\" ]; then
                            git checkout ${GIT_BRANCH} || true;
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
            
        runDevelopBuildAndUnittests()
    
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
