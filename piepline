import devops.aks.PipelineConstants
import devops.aks.DB.db

import java.util.concurrent.ConcurrentHashMap

def call(runtimeVars) {
    pipelineUtil.loadProjectConfiguration(runtimeVars)
    if (!runtimeVars.OrgPvcCacheName) {
        colorLog.errorLog("OrgPvcCacheName must be configured")
    }
    if (condition.enableDeployProd()){
        runtimeVars.nodeSelector = PipelineConstants.PROD_NODE_SELECTOR
        runtimeVars.webhookCredentialID = runtimeVars.webhookProdCredentialID
    }
    if(runtimeVars.compileByNodejs){
        def rawName = pipelineUtil.getOrgName() + "-" + pipelineUtil.getProjectName()
        def pvcName = rawName.toLowerCase().replaceAll("_","-") + "-csi"
        println(pvcName)
        pipelineUtil.autoCreatePvc(pvcName, runtimeVars)
        runtimeVars.OrgPvcCacheName = pvcName
    }
    def agentPodTemplate = libraryResource 'agentPodTemplate/k8sCompilePodTemplate.yaml'
    def templateVars = [
            'compileByGradle': "${runtimeVars.compileByGradle}".toBoolean(),
            'compileByMaven': "${runtimeVars.compileByMaven}".toBoolean(),
            'compileByNodejs': "${runtimeVars.compileByNodejs}".toBoolean(),
            'OrgPvcCacheName': "${runtimeVars.OrgPvcCacheName}",
            'initGradleConfigMapName': "${runtimeVars.initGradleConfigMapName}",
            'nodeSelector': "${runtimeVars.nodeSelector}",
            'limitCpu': "${runtimeVars.limitCpu}",
            'limitMemory': "${runtimeVars.limitMemory}",
            'requestCpu': "${runtimeVars.requestCpu}",
            'requestMemory': "${runtimeVars.requestMemory}",
            'jenkinsSharedPvc': PipelineConstants.JENKINS_SHARED_PVC
    ]
    println(templateVars)
    def pod = renderTemplate(agentPodTemplate, templateVars)
    println(pod)

    // store CI build result in hashmap and insert to devops database after build completed
    Map<String, List[]> ciResultMap = new ConcurrentHashMap<>()
    // CI build env for detail CI build data
    def ciBuildEnv = ''
    // CI build date time
    def build_datetime = new Date().format('yyyyMMdd').toBigInteger()
    def runtimeStageTaskMap = new HashMap()
    boolean CIResult = PipelineConstants.CI_RESULT_FAIL
    boolean CDResult = PipelineConstants.CD_RESULT_FAIL
    String checkboxJson = pipelineUtil.processCheckboxParameters(runtimeVars, runtimeVars.compileByGradle)
    pipeline {
        agent {
            kubernetes {
                // 每个team使用独立的cache pvc
                // 编译cache挂载目录/cache，为了防止并发干扰每个项目独立的cache目录，配置环境变量GRADLE_USER_HOME=/cache/${projectName}/gradle，SONAR_USER_HOME=/cache/${projectName}/sonar，
                // 初次创建pvc可能会构建失败，重试即可
                inheritFrom "${runtimeVars.inheritType}"
                yamlMergeStrategy merge()
                yaml pod
            }
        }
        options {
            ansiColor('xterm')
            disableConcurrentBuilds()
            buildDiscarder(logRotator(daysToKeepStr: "${runtimeVars.buildDaysToDiscard}", artifactDaysToKeepStr: "${runtimeVars.buildDaysToDiscard}", numToKeepStr: "${runtimeVars.buildNumberToDiscard}", artifactNumToKeepStr: "${runtimeVars.buildNumberToDiscard}"))
        }
        parameters {
            checkboxParameter(name: 'CITools', format: 'JSON',
                    pipelineSubmitContent: checkboxJson, description: 'choose CI tools')
            booleanParam(name: 'Deployment', defaultValue: false, description: 'check to run Deployment')
        }
        environment {
            taskList = "${params.CITools}".split(",").toList()
        }
        stages {
            stage('Prepare') {
                steps {
                    container('executor') {
                        script {

                            if (pipelineUtil.isBranch("${env.GIT_BRANCH}" , "${runtimeVars.deployStageBranch}")){
                                runtimeVars.webhookCredentialID = runtimeVars.webhookStageCredentialID
                            }
                            if(runtimeVars.compileByNodejs){
                                pipelineUtil.setFrontEndCache(runtimeVars.NpmRegistryUrl, runtimeVars.JFrogAuthCredentialID, runtimeVars.cleanCache)
                            } else if(runtimeVars.compileByMaven) {
                                pipelineUtil.setMavenCache(runtimeVars.cleanCache)
                            } else {
                                pipelineUtil.setGradleCache(runtimeVars.cleanCache)
                            }
                            // pre set oam file repo url from env.GIT_URL
                            runtimeVars.oamDEVRepo = env.GIT_URL.replace(".git","-dev.git").toLowerCase()
                            runtimeVars.oamQARepo = env.GIT_URL.replace(".git","-qa.git").toLowerCase()
                            runtimeVars.oamStageRepo = env.GIT_URL.replace(".git","-stage.git").toLowerCase()
                            runtimeVars.oamProdRepo = env.GIT_URL.replace(".git","-prod.git").toLowerCase()
                            // get git version number as image tag
                            runtimeVars.gitVersionNumber = pipelineUtil.getGitVersionNumber()
                            runtimeVars.imageTimeStamp = pipelineUtil.getImageTimeStamp()
                            pipelineUtil.initBuildUser(runtimeVars.enableSharedSonarHome)
                            pipelineUtil.setNexusRepoInfo(runtimeVars)
                            sh("env")
                            runtimeStageTaskMap  = condition.getCICDTaskMap(runtimeVars,"${taskList}")
                            // to get which environment CI tools is running in
                            ciBuildEnv = pipelineUtil.getCIBuildEnv(runtimeStageTaskMap)

                            if (runtimeStageTaskMap.get(PipelineConstants.GITHUB, false)){
                                def cloc = new devops.aks.CI.cloc()
                                cloc.execute(ciResultMap, build_datetime, ciBuildEnv, runtimeVars.gitVersionNumber)
                            }
                            //CI CD 动态阶段生成
                            def CICDSequence = ["CI","CD"]
                            def failFast = true
                            //DEPLOY_QA和enableCICDParallel同时满足条件，则DeployQA CD和整个CI任务并行执行。
                            runtimeVars.enableCICDParallel = runtimeStageTaskMap.get(PipelineConstants.DEPLOY_QA,false) && runtimeVars.enableCICDParallel
                            if(runtimeVars.enableCICDParallel) {
                                //数组反转，先显示CD的Stage后显示CI的Stage
                                CICDSequence = CICDSequence.reverse()
                                failFast = false
                            }

                            //Deployment and CI/CD evaluation, followed by branch deletion and creation of a new branch for deployment.
                            boolean onlyOamYamlChanged = false
                            boolean autoTrigger = deploymentUtil.checkAutoTriggerBuild();
                            println "autoTrigger: ${autoTrigger}"
                            boolean manualDeployment = params.Deployment
                            println "manualDeployment: ${manualDeployment}"

                            if (autoTrigger){
                                def bedrockYamlfile = "bedrock-application-${ciBuildEnv}.yaml"
                                def commitId = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
                                onlyOamYamlChanged = deploymentUtil.checkIfOnlyBedrockYamlChanged(commitId, bedrockYamlfile)
                                println "onlyOamYamlChanged: ${onlyOamYamlChanged}"
                            }else if (manualDeployment){
                                //main manual trigger and feature and RELEASE manual trigger
                                if (!env.GIT_BRANCH.equalsIgnoreCase(runtimeVars.deploymentYamlFromBranch)){
                                    runtimeVars.getDeploymentYamlFromBranch = true
                                }
                                boolean isQaBranch = pipelineUtil.isBranch("${env.GIT_BRANCH}" ,"${runtimeVars.deployQaBranch}")
                                if (isQaBranch && !runtimeVars.enableAutoTriggerQaCI){
                                    runtimeStageTaskMap.put(PipelineConstants.DEPLOY_QA, true)
                                }
                            }

                            //1.  The pipeline was manually triggered for deployment ('manualDeployment' is true).
                            //2.  The pipeline was automatically triggered ('autoTrigger' is true) AND only OAM YAML files were changed ('onlyOamYamlChanged' is true).
                            runtimeVars.isDeployment = manualDeployment || (autoTrigger && onlyOamYamlChanged)
                            println "isDeployment: ${runtimeVars.isDeployment}"
                            def jobCICDList = CICDSequence.collectEntries {
                                // for each entry return a list containing [key, value]
                                ["${it}": { stageName ->
                                    return {
                                        stage("${stageName}") {
                                            container('executor') {
                                                script {
                                                    if ("CI".equals(stageName)){
                                                        if (!runtimeVars.isDeployment && runtimeVars.enableSkipCI && !condition.enableDeployProd()){
                                                            skipciUtil.findCommitScanResultByFile(runtimeVars, "${taskList}")
                                                        }
                                                        def ci = new devops.aks.CI.ci()
                                                        ci.execute(ciResultMap, build_datetime, ciBuildEnv, runtimeStageTaskMap, runtimeVars, failFast)
                                                        CIResult = PipelineConstants.CI_RESULT_SUCCESS
                                                    }else{
                                                        def cd
                                                        def appName = pipelineUtil.getAppName()
                                                        if (runtimeVars.isDeployment){
                                                            cd = new devops.aks.CD.deploymentCD()
                                                            deploymentUtil.setDisplayName("#${env.BUILD_NUMBER}-${appName}-Deployment")
                                                        }else{
                                                            cd = new devops.aks.CD.bedrockCD()
                                                            deploymentUtil.setDisplayName("#${env.BUILD_NUMBER}-${appName}-Release")
                                                        }
                                                        cd.execute(runtimeStageTaskMap, runtimeVars)
                                                        CDResult = PipelineConstants.CD_RESULT_SUCCESS
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }(it)]
                            }
                            if(runtimeVars.enableCICDParallel){
                                //DeployQA CD和整个CI任务并行执行。
                                try{
                                    parallel jobCICDList
                                }catch (Exception e){
                                    colorLog.errorLog(e.message)
                                }finally {
                                    /*
                                   1.CD成功，CI成功，pipeline的状态为SUCCESS。
                                   2.CD成功，CI失败，pipeline的状态为UNSTABLE。
                                   3.CD失败，pipeline的状态为FAILURE。
                                   **/
                                    def buildResult = (CDResult && CIResult) ? PipelineConstants.CURRENT_BUILD_SUCCESS : (CDResult && !CIResult) ? PipelineConstants.CURRENT_BUILD_UNSTABLE: PipelineConstants.CURRENT_BUILD_FAILURE
                                    currentBuild.result = PipelineConstants.CURRENT_BUILD_UNSTABLE.equals(currentBuild.result) ? PipelineConstants.CURRENT_BUILD_UNSTABLE : buildResult
                                }
                            }else{
                                //DeployStage和DeployProd流程不变。
                                jobCICDList.each { stageName, stageClosure -> stageClosure()}
                            }
                        }
                    }
                }
            }
        }
        post {
            always {
                script{
                    container('executor') {
                        script {
                            if (!runtimeVars.isDeployment && runtimeVars.enableSkipCI && !condition.enableDeployProd()){
                                skipciUtil.saveCommitScanResultToFile(runtimeVars)
                            }
                            // store CI data to devops sql server
                            getDatabaseConnection(type: 'GLOBAL') {
                                pipelineUtil.insertDatabase(ciResultMap, build_datetime, ciBuildEnv, runtimeVars.gitVersionNumber)
                            }
                        }
                    }
                }
            }
            success {
                script {
                    if (condition.isCDRun(runtimeStageTaskMap)){
                        sendNotifyMessage(runtimeVars.webhookCredentialID, "${env.BUILD_USER_ID}", pipelineUtil.getAppName(),PipelineConstants.CURRENT_BUILD_SUCCESS)
                    }
                }
            }
            unstable {
                script {
                    sendNotifyMessage(runtimeVars.webhookCredentialID, "${env.BUILD_USER_ID}", pipelineUtil.getAppName(),PipelineConstants.CURRENT_BUILD_UNSTABLE)
                }
            }
            failure {
                script {
                    sendNotifyMessage(runtimeVars.webhookCredentialID, "${env.BUILD_USER_ID}", pipelineUtil.getAppName(),PipelineConstants.CURRENT_BUILD_FAILURE)
                }
            }
        }
    }
}

