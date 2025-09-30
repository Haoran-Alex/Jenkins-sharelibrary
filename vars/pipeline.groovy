
import vars.pipelineUtil

def call(runtimeVars){
        if(runtimeVars.compileByNodejs){
        def rawName = pipelineUtil.getOrgName() + "-" + pipelineUtil.getProjectName()

        println(rawName)
    }
}
