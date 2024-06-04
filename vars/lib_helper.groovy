def call(Map config) {
    def triggerRef = config.containsKey('trigger_ref') ? config.trigger_ref : '$.push.changes[0].old.name'
    def triggerRegexpFilter = config.containsKey('trigger_regexp_filter') ? config.trigger_regexp_filter : '^(development|uat)'

    if (config.containsKey("github_hook") && config.github_hook) {
        properties([pipelineTriggers([githubPush()])])
    }

    pipeline {
        agent { label config.agent }

        options {
            timeout(time: 25, unit: 'MINUTES')
            buildDiscarder(logRotator(numToKeepStr: '15', artifactNumToKeepStr: '15'))
            disableConcurrentBuilds()
        }

        parameters {
            string(name: 'BRANCH', description: 'Branch to build', defaultValue: '')
        }

        triggers {
            GenericTrigger(
                genericVariables: [
                    [key: 'REF', value: triggerRef],
                ],
                causeString: 'Triggered by Remote Event',
                token: 'bitbucket_' + config.sonar_qube_project_key,
                printContributedVariables: false,
                printPostContent: false,
                silentResponse: false,
                shouldNotFlattern: false,

                regexpFilterText: '$REF',
                regexpFilterExpression: triggerRegexpFilter
            )
        }

        stages {
            stage("Configure Init") {
                steps {
                    script {
                        configureInit(config)
                    }
                }
            }

            stage("Checkout Project Code") {
                steps {
                    checkout scm: [
                        $class: "GitSCM",
                        branches: [[name: config.target_branch.startsWith("refs") ? config.target_branch : "refs/heads/${config.target_branch}"]],
                        submoduleCfg: [],
                        userRemoteConfigs: [
                            config.scm_global_config
                        ]
                    ]
                }
            }

            stage("Read Project Config") {
                steps {
                    script {
                        // Create config file variable
                        config.config_file = config.containsKey('config_file_path') ? config.config_file_path : ".jenkins/buildspec.yaml"
                        
                        // Check if file exists
                        if (fileExists(config.config_file)) {
                            config.b_config = readYaml file: config.config_file
                        } else {
                            error "Config file not found: ${config.config_file}"
                        }

                        // Check if b_config is properly loaded
                        if (config.b_config == null) {
                            error "Failed to load config from file: ${config.config_file}"
                        }

                        config.job_base = sh(
                            script: "python3 -c 'print(\"/\".join(\"${JOB_NAME}\".split(\"/\")[:-1]))'",
                            returnStdout: true
                        ).trim()
                        config.job_name = sh(
                            script: "python3 -c 'print(\"${JOB_NAME}\".split(\"/\")[-1])'",
                            returnStdout: true
                        ).trim()

                        // Configure commit ID for project
                        commitID = sh(
                            script: """
                            git log --pretty=format:"%h" | head -1
                            """,
                            returnStdout: true
                        ).trim()

                        // Define variable for container build
                        config.b_config.imageTag = commitID
                        config.b_config.imageLatestTag = "latest"

                        config.commitID = commitID

                        if (config.b_config.containsKey("sequentialDeploymentMapping")) {
                            config.sequential_deployment_mapping = config.b_config.sequentialDeploymentMapping[config.target_branch]
                        }
                    }
                }
            }

            stage("Change Version Number") {
                when {
                    expression {
                        return config.b_config?.controllers?.versionNumberController
                    }
                }
                steps {
                    script {
                        lib_versionController(config)
                    }
                }
            }

            stage("Run Unit tests") {
                when {
                    expression {
                        return config.b_config?.controllers?.unitTestController && 
                            config.b_config.controllers.buildController
                    }
                }
                steps {
                    script {
                        lib_unitTestController(config)
                    }
                }
            }

            stage("Build Project") {
                when {
                    expression {
                        return config.b_config?.controllers?.buildController
                    }
                }
                steps {
                    script {
                        lib_buildController(config)
                    }
                }
            }

            stage("Build and Publish as a Container") {
                when {
                    expression {
                        return config.b_config?.controllers?.containerController
                    }
                }
                steps {
                    script {
                        lib_containerController(config)
                    }
                }
            }

            stage("Security Scan For Container") {
                when {
                    expression {
                        return config.scan_container_image
                    }
                }
                steps {
                    sh """
                    echo ${config.containerImages.join("\n")} > anchore_images
                    """
                    anchore name: "anchore_images", bailOnFail: false
                }
            }
        }

        post {
            always {
                script {
                    try {
                        lib_cleanupController(config)
                    } catch (Exception e) {
                        echo "Error during cleanup: ${e.message}"
                    }
                    try {
                        lib_postbuildController(config)
                    } catch (Exception e) {
                        echo "Error during post-build: ${e.message}"
                    }
                }

                script {
                    try {
                        withCredentials([string(credentialsId: 'teams-webhook-url', variable: 'URL_WEBHOOK')]) {
                            office365ConnectorSend webhookUrl: "${URL_WEBHOOK}"
                        }
                    } catch (_) {
                        echo "Teams credential does not exist, skipping."
                    }
                }
            }
            success {
                script {
                    def publisher = LastChanges.getLastChangesPublisher "PREVIOUS_REVISION", "SIDE", "LINE", true, true, "", "", "", "", ""
                    publisher.publishLastChanges()
                    def htmlDiff = publisher.getHtmlDiff()
                    writeFile file: 'build-diff.html', text: htmlDiff

                    lib_helper.triggerJob(config)
                }
            }
        }
    }
}
