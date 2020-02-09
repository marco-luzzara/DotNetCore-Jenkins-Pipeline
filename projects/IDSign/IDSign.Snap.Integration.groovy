properties(
    [parameters(
        [
            string(name: 'pipeline_configuration_json',
                defaultValue: '{}',
                description: 'json configuration to initialize a pipeline'),
            booleanParam(name: 'manual_build', 
                defaultValue: false, 
                description: 'flag to decide if taking json configuration from build parameters or VCS repo')
        ]
    )]
)

@Library('pipeline-customizer') _

import utils.PipelineStages;
import versioning.IDSignVersioningSystem;
import interfaces.pipeline.stage.decorator.GenericStageDecorator;
import static utils.script.ScriptAdapter.log;

def credentialsId = "your-credentials-id";

class BuildStageCustomized extends GenericStageDecorator {
    public BuildStageCustomized(Map stageConfig = [:]) {
        super(stageConfig);
    }

    @Override
    def run()
    {
        super.run();
        log("overridden method run has been called");
    }

    @Override
    void rollback()
    {
        super.rollback();
        log("overridden method rollback has been called");
    }
}

node('unix00') {
    cleanWs();

    def configMap = pipelineUtil.getPipelineConfig(params.pipeline_configuration_json, params.manual_build, credentialsId);
    def privateConfigMap = pipelineUtil.getPipelinePrivateConfig(credentialsId, 'IDSignPrivateConfig.json');

    def versioningSystem = new IDSignVersioningSystem([
        versioningServerEndpoint: configMap.versioningServerEndpoint,
        prjManagePortalUrl: configMap.prjManagePortalUrl,
        prjManagePortalUserName: privateConfigMap.jenkinsUserName,
        prjManagePortalUserPwd: privateConfigMap.jenkinsUserPwd
    ])

    def pipeline = pipelineUtil.createDotNetCorePipeline(configMap, privateConfigMap);

    pipeline.injectVersioningSystem(versioningSystem);
    pipeline.injectCustomizeStage(PipelineStages.BUILD, new BuildStageCustomized());

    stage('Checkout') {
        pipeline.executeStage(PipelineStages.CHECKOUT);
    }

    try {
        slackUtil.notifyBuildStarted();
        
        stage('Restore') {
            pipeline.executeStage(PipelineStages.RESTORE);
        }
        
        stage('Clean') {
            pipeline.executeStage(PipelineStages.CLEAN);
        }
        
        stage('Build') {
            pipeline.executeStage(PipelineStages.BUILD);
        }
        
        stage('Unit test') {
            pipeline.executeStage(PipelineStages.UNITTEST);
        }
        
        stage('Integration Test') {
            pipeline.executeStage(PipelineStages.INTEGRATIONTEST);
        }
        
        stage('Versioning') {
            pipeline.executeStage(PipelineStages.VERSIONING);
        }
        
        stage('Pack') {
            pipeline.executeStage(PipelineStages.PACK);
        }

        stage('Push Package') {
            pipeline.executeStage(PipelineStages.PUSHPACKAGE);
        }

        stage('Publish on Artifactory') {
            pipeline.executeStage(PipelineStages.PUBLISHARTIFACT);
        }

        pipeline.executeStage(PipelineStages.COMPLETE)

        slackUtil.notifyBuildSucceeded();
    }
    catch (InterruptedException exc) {
        slackUtil.notifyBuildAborted();

        throw exc;
    }
    catch (Exception | AssertionError exc) {
        slackUtil.notifyBuildFailed(exc.message);

        throw exc;
    }
}