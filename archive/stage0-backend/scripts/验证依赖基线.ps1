param(
    [Parameter(Mandatory = $true)][string]$JdkHome,
    [Parameter(Mandatory = $true)][string]$EvidenceDirectory
)

$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path -Parent $PSScriptRoot
$jdk = (Resolve-Path -LiteralPath $JdkHome).Path
$evidence = [IO.Path]::GetFullPath($EvidenceDirectory)
# clean 会清除 target；证据必须单独保存，且不覆盖既有运行。
if ($evidence.StartsWith((Join-Path $backendRoot 'target'), [StringComparison]::OrdinalIgnoreCase)) {
    throw 'EvidenceDirectory must be outside backend/target'
}
if (Test-Path -LiteralPath $evidence) { throw 'EvidenceDirectory already exists; choose a new run directory' }
New-Item -ItemType Directory -Path $evidence -Force | Out-Null
$oldJavaHome = $env:JAVA_HOME
$oldJavaOptions = $env:JAVA_TOOL_OPTIONS
$env:JAVA_HOME = $jdk
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'
$commands = [Collections.Generic.List[object]]::new()

function Invoke-MavenEvidence([string]$Name, [string[]]$MavenArguments) {
    $output = Join-Path $evidence ($Name + '.txt')
    & (Join-Path $backendRoot 'mvnw.cmd') @MavenArguments *> $output
    $exitCode = $LASTEXITCODE
    $commands.Add([ordered]@{name=$Name;arguments=$MavenArguments;exitCode=$exitCode})
    $commands | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $evidence 'commands.json') -Encoding utf8
    if ($exitCode -ne 0) { throw "Maven failed ($Name); see $output" }
}

Push-Location -LiteralPath $backendRoot
try {
    $javaOutput = & (Join-Path $jdk 'bin/java.exe') -version 2>&1 | Out-String
    $javaOutput | Set-Content -LiteralPath (Join-Path $evidence 'java-version.txt') -Encoding utf8
    Copy-Item -LiteralPath (Join-Path $jdk 'release') -Destination (Join-Path $evidence 'jdk-release.txt')
    Invoke-MavenEvidence 'maven-version' @('-version')
    $environment = [ordered]@{
        capturedAt=[DateTimeOffset]::Now.ToString('o')
        os=[System.Runtime.InteropServices.RuntimeInformation]::OSDescription
        architecture=[System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()
        jdkHome=$jdk
        baselineCommit=(& git rev-parse HEAD)
        branch=(& git branch --show-current)
        targetJdk='Java 17; selected vendor, patch and OS artifact provenance are recorded separately'
        targetAdmission='NOT_PROVEN_BY_THIS_SCRIPT'
        database='Isolated in-memory H2; no business schema or DDL'
        externalServices='No model, MCP, or MinIO network methods invoked'
    }
    $environment | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'environment.json') -Encoding utf8
    $baseArguments = @('-B', '-ntp')
    Invoke-MavenEvidence 'effective-pom-command' ($baseArguments + @('help:effective-pom', "-Doutput=$evidence/effective-pom.xml"))
    Invoke-MavenEvidence 'dependency-tree-command' ($baseArguments + @('dependency:tree', '-Dverbose', "-DoutputFile=$evidence/dependency-tree.txt"))
    Invoke-MavenEvidence 'classpath-command' ($baseArguments + @('dependency:build-classpath', '-DincludeScope=test', "-Dmdep.outputFile=$evidence/classpath.txt"))
    Invoke-MavenEvidence 'clean-verify' ($baseArguments + @('clean', 'verify'))
    Copy-Item -LiteralPath (Join-Path $backendRoot 'target/issue24/autoconfiguration.txt'),
        (Join-Path $backendRoot 'target/issue24/runtime-class-origins.tsv') -Destination $evidence
    Copy-Item -Path (Join-Path $backendRoot 'target/surefire-reports/TEST-*.xml') -Destination $evidence
    $results = foreach ($report in Get-ChildItem -LiteralPath $evidence -Filter 'TEST-*.xml') {
        [xml]$suite = Get-Content -LiteralPath $report.FullName -Raw
        [ordered]@{suite=$suite.testsuite.name;tests=[int]$suite.testsuite.tests;
            failures=[int]$suite.testsuite.failures;errors=[int]$suite.testsuite.errors;skipped=[int]$suite.testsuite.skipped}
    }
    $results | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'test-results.json') -Encoding utf8
    $jar = Join-Path $backendRoot 'target/agentscope-brain-backend-0.1.0-SNAPSHOT.jar'
    $firstHash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
    # 第二次仅独立 clean 编译／打包，验证产物可复现，不重复执行已经通过的测试。
    Invoke-MavenEvidence 'rebuild-package' ($baseArguments + @('clean', 'package', '-Dmaven.test.skip=true'))
    $secondHash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
    [ordered]@{firstSha256=$firstHash;secondSha256=$secondHash;equal=($firstHash -eq $secondHash)} |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'reproducible-jar.json') -Encoding utf8
    if ($firstHash -ne $secondHash) { throw 'Independent clean builds produced different JAR hashes' }
    $classFiles = Get-ChildItem -LiteralPath (Join-Path $backendRoot 'target/classes') -Recurse -Filter '*.class'
    $bytecode = foreach ($classFile in $classFiles) {
        $bytes = [IO.File]::ReadAllBytes($classFile.FullName)
        $major = ([int]$bytes[6] * 256) + [int]$bytes[7]
        if ($major -ne 61) { throw "Unexpected class major version $major" }
        [ordered]@{file=[IO.Path]::GetRelativePath($backendRoot,$classFile.FullName);major=$major}
    }
    $bytecode | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'bytecode.json') -Encoding utf8
    $classpath = (Get-Content -LiteralPath (Join-Path $evidence 'classpath.txt') -Raw).Trim()
    $artifactHashes = foreach ($artifact in $classpath.Split([IO.Path]::PathSeparator)) {
        [ordered]@{path=$artifact;sha256=(Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant()}
    }
    $artifactHashes | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'dependency-sha256.json') -Encoding utf8
    $inputs = @('pom.xml','mvnw','mvnw.cmd','.mvn/wrapper/maven-wrapper.properties') +
        @(Get-ChildItem -LiteralPath 'src','scripts' -File -Recurse | ForEach-Object { [IO.Path]::GetRelativePath($backendRoot,$_.FullName) })
    $inputs | Sort-Object -Unique | ForEach-Object {
        [ordered]@{path=$_;sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()}
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'source-sha256.json') -Encoding utf8
    Write-Output "Development probes passed. Check the recorded JDK artifact provenance for this run: $evidence"
} finally {
    Pop-Location
    $env:JAVA_HOME = $oldJavaHome
    $env:JAVA_TOOL_OPTIONS = $oldJavaOptions
}
