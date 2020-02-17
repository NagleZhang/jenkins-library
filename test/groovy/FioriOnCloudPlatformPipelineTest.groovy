import util.CommandLineMatcher
import util.JenkinsLockRule
import util.JenkinsWithEnvRule
import util.JenkinsWriteFileRule

import static org.hamcrest.Matchers.allOf
import static org.hamcrest.Matchers.containsString
import static org.hamcrest.Matchers.equalTo
import static org.hamcrest.Matchers.hasItem
import static org.hamcrest.Matchers.is
import static org.hamcrest.Matchers.subString
import static org.junit.Assert.assertThat

import org.hamcrest.Matchers
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

import com.sap.piper.JenkinsUtils
import com.sap.piper.PiperGoUtils

import util.BasePiperTest
import util.JenkinsCredentialsRule
import util.JenkinsReadYamlRule
import util.JenkinsShellCallRule
import util.JenkinsStepRule
import util.JenkinsReadJsonRule
import util.JenkinsWriteFileRule
import util.Rules

class FioriOnCloudPlatformPipelineTest extends BasePiperTest {

    /*  This scenario builds a fiori app and deploys it into an neo account.
        The build is performed using mta, which delegates to grunt. grunt in
        turn makes use of the 'sap/grunt-sapui5-bestpractice-build' plugin.
        The dependencies are resolved via npm.

        In order to run the scenario the project needs to fullfill these
        prerequisites:

        Build tools:
        *   mta.jar available
        *   npm installed

        Project configuration:
        *   sap registry `@sap:registry=https://npm.sap.com` configured in
            .npmrc (either in the project or on any other suitable level)
        *   dependency to `@sap/grunt-sapui5-bestpractice-build` declared in
            package.json
        *   npmTask `@sap/grunt-sapui5-bestpractice-build` loaded inside
            Gruntfile.js and configure default tasks (e.g. lint, clean, build)
        *   mta.yaml
    */

    JenkinsStepRule stepRule = new JenkinsStepRule(this)
    JenkinsReadYamlRule readYamlRule = new JenkinsReadYamlRule(this)
    JenkinsShellCallRule shellRule = new JenkinsShellCallRule(this)
    private JenkinsLockRule jlr = new JenkinsLockRule(this)
    JenkinsWriteFileRule writeFileRule = new JenkinsWriteFileRule(this)
    JenkinsReadJsonRule readJsonRule = new JenkinsReadJsonRule(this)

    @Rule
    public RuleChain ruleChain = Rules
        .getCommonRules(this)
        .around(readYamlRule)
        .around(stepRule)
        .around(shellRule)
        .around(jlr)
        .around(new JenkinsWithEnvRule(this))
        .around(new JenkinsCredentialsRule(this)
        .withCredentials('CI_CREDENTIALS_ID', 'foo', 'terceSpot'))
        .around(writeFileRule)
        .around(readJsonRule)

    @Before
    void setup() {
        //
        // needed since we have dockerExecute inside mtaBuild
        JenkinsUtils.metaClass.static.isPluginActive = {def s -> false}

        //
        // there is a check for the mta.yaml file and for the deployable test.mtar file
        helper.registerAllowedMethod('fileExists', [String],{

            it ->

            // called inside mtaBuild, this file contains build config
            it == 'mta.yaml' ||

            // called inside neo deploy, this file gets deployed
            it == 'test.mtar'
        })

        helper.registerAllowedMethod("deleteDir",[], null)

        //
        // the properties below we read out of the yaml file
        readYamlRule.registerYaml('mta.yaml', ('''
                                       |ID : "test"
                                       |PATH : "."
                                       |''' as CharSequence).stripMargin())

        binding.setVariable('scm', null)

        helper.registerAllowedMethod('pwd', [], { return "./" })

        shellRule.setReturnValue(JenkinsShellCallRule.Type.REGEX, "\\./piper.*--contextConfig", '{"dummy": "only"}')

        nullScript.commonPipelineEnvironment.metaClass.readFromDisk = {Script s -> System.err<<"\nINSIDE READ FROM DISK\n"; nullScript.commonPipelineEnvironment.mtarFilePath = 'test.mtar'}
    }

    @After
    public void tearDown() {
        nullScript.commonPipelineEnvironment.metaClass = null
    }

    @Test
    void straightForwardTest() {

        PiperGoUtils piperGoUtilsMock = new PiperGoUtils(nullScript) {
            void unstashPiperBin() {
            }
        }

        nullScript
            .commonPipelineEnvironment
                .configuration =  [steps:
                                    [neoDeploy:
                                         [neo:
                                              [ host: 'hana.example.com',
                                                account: 'myTestAccount',
                                              ]
                                         ]
                                    ]
                                ]

        stepRule.step.fioriOnCloudPlatformPipeline(script: nullScript,
            platform: 'NEO',
            piperGoUtils: piperGoUtilsMock,
        )

        //
        // the mta build call:

        assertThat(shellRule.shell, hasItem(containsString('./piper mtaBuild')))

        //
        // the deployable is exchanged between the involved steps via this property:
        assertThat(nullScript.commonPipelineEnvironment.getMtarFilePath(), is(equalTo('test.mtar')))

        //
        // the neo deploy call:
        Assert.assertThat(shellRule.shell,
            new CommandLineMatcher()
                .hasProlog("neo.sh deploy-mta")
                .hasSingleQuotedOption('host', 'hana\\.example\\.com')
                .hasSingleQuotedOption('account', 'myTestAccount')
                .hasSingleQuotedOption('password', 'terceSpot')
                .hasSingleQuotedOption('user', 'foo')
                .hasSingleQuotedOption('source', 'test.mtar')
                .hasArgument('synchronous'))
    }
}
