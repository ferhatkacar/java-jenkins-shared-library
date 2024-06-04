def configureInit(Map config) {
    // Define constraints
    config.script_base = "/scripts"
    config.scope = "global"

    // Configure repository settings
    config.scm_global_config = [url: config.scm_address]
    if ( config.scm_security ) {
        config.scm_global_config.credentialsId = "${config.scm_credentials_id}"
    }

    // Configure branch from params
    if ( params.containsKey("BRANCH") && params.BRANCH != "" ) {
        config.target_branch = params.BRANCH
        config.scope = "branch"
    }

    if (env.REF) {
        // Manipulate the env.REF to strip 'refs/heads/' prefix if it exists
        if (config.github_hook && env.REF.contains('refs/heads/')) {
            def branchName = env.REF.replaceFirst('^refs/heads/', '')
            echo "Branch name: ${branchName}"
            env.REF = branchName
        }
        config.target_branch = env.REF
    }

    buildName "${config.target_branch} - ${env.BUILD_NUMBER}"

    // SonarQube settings
    config.sonarqube_env_name = "sonarqube01"
    config.sonarqube_home = tool config.sonarqube_env_name

    // Repo settings
    // sh "git config --global --add safe.directory '${WORKSPACE}'"

    // Sequential deployment mapping
    config.sequential_deployment_mapping = [
        "dev": [
            "1_build": ["2_deploy_to_dev"]
        ],
        "test": [
            "1_build": ["3_deploy_to_test"]
        ]
    ]

    config.permit_trigger_branch = [
        "development",
        "dev",
        "uat",
        "devops",
        "devops-preprod",
    ]
}

def configureBranchDeployment(Map config, String sshKeyFile) {
    // SSH key file permission
    sh "chmod 600 ${sshKeyFile}"

    config.b_config.deploy.each { it ->
        String yml = writeYaml returnText: true, data: it.deploy
        sh """
        ${config.script_base}/branch-controller/controller.py -r ${it.repo} --deploy-config "${yml}" --application-path ${it.path.replace('/{environment}', '')}/branch --branch ${config.target_branch} --key-file "${sshKeyFile}"
        """
    }
}

def triggerJob(Map config) {
    try {
        // Determine the next job based on current job and target branch
        def nextJobs = config.sequential_deployment_mapping[config.job_name][config.target_branch]
        if (nextJobs) {
            nextJobs.each { jobName ->
                build job: "${config.job_base}/${jobName}", propagate: false, wait: false, parameters: [string(name: 'IMAGE', value: config.b_config.imageTag)]
            }
        } else {
            echo "No job found in sequential deployment mapping for ${config.job_name} and ${config.target_branch}"
        }
    } catch (Exception e) {
        echo "Error triggering next job: ${e.message}"
    }
}