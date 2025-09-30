import groovy.json.JsonOutput
import java.text.SimpleDateFormat

def getProjectName() {
    return "${env.JOB_NAME}".split("/")[-2]
}

def getAppName() {
    return "${env.JOB_NAME}".split("/")[-2].toLowerCase()
}

// remove space of organization name and toLowerCase
def getOrganization() {
    return "${env.JOB_NAME}".split("/")[-3].replaceAll(/\s+/, '')
}

def getOrgName() {
    return "${env.JOB_NAME}".split("/")[-4]
}

def codeCheckout(repoUrl, targetDir, branch, credentialsId,retryTimes = 3) {
    retry(retryTimes) {
        checkout([
                $class           : 'GitSCM', branches: [[name: "${branch}"]], doGenerateSubmoduleConfigurations: false,
                extensions       : [[$class: 'RelativeTargetDirectory', relativeTargetDir: "${targetDir}"],[$class: 'CleanBeforeCheckout', deleteUntrackedNestedRepositories: true],[$class: 'LocalBranch', localBranch: "${branch}"]],
                submoduleCfg     : [],
                userRemoteConfigs: [[credentialsId: "${credentialsId}", url: "${repoUrl}"]]
        ])
    }
}