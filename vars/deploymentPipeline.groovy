// Set up the job parameters
private def getJobParams(envs, svcs) {
    p = []
    svcs.each { key, data ->
        p.add([$class: 'BooleanParameterDefinition', name: data['paramName'], defaultValue: true, description: "Deploy ${data['displayName']}"])
    }

    choices = []
    envs.each { key, data ->
        choices.add(data['env'])
    }
    p.add([$class: 'ChoiceParameterDefinition', name: 'ENV', choices: choices, description: 'The target environment'])

    return p
    //return input(id: 'userInput', message: 'input parameters', parameters: parameters)
}


// Parse the parameters for a specific job run
private def parseParams(envs, svcs) {
    imagesToCopy = []
    servicesToSkip = envs[params.ENV]['skip']

    if (envs[params.ENV]['copyImages']) {
        svcs.each { key, data ->
            if (params.get(data['paramName'])) imagesToCopy.add(data['srcImage'])
            else servicesToSkip.add(data['templateName'])
        }
    }

    return [envConfig: envs[params.ENV], imagesToCopy: imagesToCopy, servicesToSkip: servicesToSkip]
}

// Create a deployment pipeline job given an environment and service config
def call(p = [:]) {
    envs = p['environments']
    svcs = p['services']

    properties([parameters(getJobParams(envs, svcs))])
    parsed = parseParams(envs, svcs)
    imagesToCopy = parsed['imagesToCopy']
    servicesToSkip = parsed['servicesToSkip']
    envConfig = parsed['envConfig']

    echo "imagesToCopy:   ${imagesToCopy}"
    echo "servicesToSkip: ${servicesToSkip}"
    echo "envConfig:      ${envConfig}"

    openShift.withNode(defaults: true) {
        if (imagesToCopy) {
            promoteImages(
                srcImages: parsed['imagesToCopy'],
                dstProject: envConfig['project'],
                dstCredentialsId: envConfig['builderSecretId'],
            )
        }

        if (envConfig['deployerSecretId']) {
            withCredentials([string(credentialsId: envConfig['deployerSecretId'], variable: 'TOKEN')]) {
                sh "oc login https://${envConfig['cluster']} --token=${TOKEN}"
            }
        } else {
            sh "oc login https://${envConfig['cluster']}"
        }

        sh "oc project ${envConfig['project']}"

        deployServiceSet(
            serviceSet: envConfig['serviceSet'],
            skip: servicesToSkip,
            env: envConfig['env'],
            project: envConfig['project'],
            secretsSrcProject: envConfig['secretsSrcProject'],
        )
    }
}