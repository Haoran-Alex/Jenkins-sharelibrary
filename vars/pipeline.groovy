import java.util.concurrent.ConcurrentHashMap

def call(runtimeVars){
        if(runtimeVars.compileByNodejs){
        def rawName = pipelineUtil.getOrgName() + "-" + pipelineUtil.getProjectName()

        println(rawName)
    }
}