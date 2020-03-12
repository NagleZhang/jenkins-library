package maven

import (
	"bytes"
	"fmt"
	"io"
	"strings"

	"github.com/SAP/jenkins-library/pkg/http"
	"github.com/SAP/jenkins-library/pkg/log"
	"github.com/SAP/jenkins-library/pkg/piperutils"
)

type ExecuteOptions struct {
	PomPath                     string   `json:"pomPath,omitempty"`
	ProjectSettingsFile         string   `json:"projectSettingsFile,omitempty"`
	GlobalSettingsFile          string   `json:"globalSettingsFile,omitempty"`
	M2Path                      string   `json:"m2Path,omitempty"`
	Goals                       []string `json:"goals,omitempty"`
	Defines                     []string `json:"defines,omitempty"`
	Flags                       []string `json:"flags,omitempty"`
	LogSuccessfulMavenTransfers bool     `json:"logSuccessfulMavenTransfers,omitempty"`
	ReturnStdout                bool     `json:"returnStdout,omitempty"`
}

type mavenExecRunner interface {
	Stdout(out io.Writer)
	Stderr(err io.Writer)
	RunExecutable(e string, p ...string) error
}

const mavenExecutable = "mvn"

func Execute(options *ExecuteOptions, command mavenExecRunner) (string, error) {
	stdOutBuf, stdOut := evaluateStdOut(options)
	command.Stdout(stdOut)
	command.Stderr(log.Entry().Writer())

	parameters, err := getParametersFromOptions(options, &http.Client{})
	if err != nil {
		return "", fmt.Errorf("failed to construct parameters from options: %w", err)
	}

	err = command.RunExecutable(mavenExecutable, parameters...)
	if err != nil {
		commandLine := append([]string{mavenExecutable}, parameters...)
		return "", fmt.Errorf("failed to run executable, command: '%s', error: %w", commandLine, err)
	}

	if stdOutBuf == nil {
		return "", nil
	}
	return string(stdOutBuf.Bytes()), nil
}

func evaluateStdOut(config *ExecuteOptions) (*bytes.Buffer, io.Writer) {
	var stdOutBuf *bytes.Buffer
	var stdOut io.Writer

	stdOut = log.Entry().Writer()
	if config.ReturnStdout {
		stdOutBuf = new(bytes.Buffer)
		stdOut = io.MultiWriter(stdOut, stdOutBuf)
	}
	return stdOutBuf, stdOut
}

func getParametersFromOptions(options *ExecuteOptions, client http.Downloader) ([]string, error) {
	var parameters []string

	if options.GlobalSettingsFile != "" {
		globalSettingsFileName := options.GlobalSettingsFile
		if strings.HasPrefix(options.GlobalSettingsFile, "http:") || strings.HasPrefix(options.GlobalSettingsFile, "https:") {
			err := downloadSettingsFromURL(options.ProjectSettingsFile, "globalSettings.xml", client)
			if err != nil {
				return nil, err
			}
			globalSettingsFileName = "globalSettings.xml"
		}
		parameters = append(parameters, "--global-settings", globalSettingsFileName)
	}

	if options.ProjectSettingsFile != "" {
		projectSettingsFileName := options.ProjectSettingsFile
		if strings.HasPrefix(options.ProjectSettingsFile, "http:") || strings.HasPrefix(options.ProjectSettingsFile, "https:") {
			err := downloadSettingsFromURL(options.ProjectSettingsFile, "projectSettings.xml", client)
			if err != nil {
				return nil, err
			}
			projectSettingsFileName = "projectSettings.xml"
		}
		parameters = append(parameters, "--settings", projectSettingsFileName)
	}

	if options.M2Path != "" {
		parameters = append(parameters, "-Dmaven.repo.local="+options.M2Path)
	}

	if options.PomPath != "" {
		parameters = append(parameters, "--file", options.PomPath)
	}

	if options.Flags != nil {
		parameters = append(parameters, options.Flags...)
	}

	if options.Defines != nil {
		parameters = append(parameters, options.Defines...)
	}

	parameters = append(parameters, "--batch-mode")

	if options.LogSuccessfulMavenTransfers {
		parameters = append(parameters, "-Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=warn")
	}

	parameters = append(parameters, options.Goals...)
	return parameters, nil
}

// ToDo replace with pkg/maven/settings GetSettingsFile
func downloadSettingsFromURL(url, filename string, client http.Downloader) error {
	err := client.DownloadFile(url, filename, nil, nil)
	if err != nil {
		return fmt.Errorf("failed to download maven settings from URL '%s' to file '%s': %w",
			url, filename, err)
	}
	return nil
}

func GetTestModulesExcludes() []string {
	var excludes []string
	exists, _ := piperutils.FileExists("unit-tests/pom.xml")
	if exists {
		excludes = append(excludes, "-pl", "!unit-tests")
	}
	exists, _ = piperutils.FileExists("integration-tests/pom.xml")
	if exists {
		excludes = append(excludes, "-pl", "!integration-tests")
	}
	return excludes
}
