param(
    [string]$JdkHome = $env:JAVA_HOME
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$version = "1.0.0"
$appName = "VideoGrab"

function Find-JdkHome {
    param([string]$Preferred)

    if ($Preferred -and (Test-Path (Join-Path $Preferred "bin\jpackage.exe"))) {
        return (Resolve-Path $Preferred).Path
    }

    $fromPath = Get-Command jpackage.exe -ErrorAction SilentlyContinue
    if ($fromPath) {
        return (Resolve-Path (Join-Path (Split-Path -Parent $fromPath.Source) "..")).Path
    }

    $candidates = @(
        "C:\Program Files\Java\jdk-24",
        "C:\Program Files\Java\jdk-23",
        "C:\Program Files\Java\jdk-22",
        "C:\Program Files\Java\jdk-21"
    )

    $searchRoots = @(
        "C:\Program Files\Java",
        "C:\Program Files\Eclipse Adoptium",
        "C:\Program Files\Microsoft"
    )

    foreach ($searchRoot in $searchRoots) {
        if (Test-Path $searchRoot) {
            $candidates += Get-ChildItem $searchRoot -Directory -Filter "jdk*" -ErrorAction SilentlyContinue |
                Sort-Object Name -Descending |
                ForEach-Object { $_.FullName }
        }
    }

    foreach ($candidate in $candidates) {
        if (Test-Path (Join-Path $candidate "bin\jpackage.exe")) {
            return $candidate
        }
    }

    throw "JDK with jpackage was not found. Install JDK 21+ or pass -JdkHome C:\Path\To\JDK."
}

$jdk = Find-JdkHome $JdkHome
$javac = Join-Path $jdk "bin\javac.exe"
$jar = Join-Path $jdk "bin\jar.exe"
$jpackage = Join-Path $jdk "bin\jpackage.exe"

$repo = Join-Path $env:USERPROFILE ".m2\repository"
$dependencies = @(
    "$repo\com\fasterxml\jackson\core\jackson-annotations\2.17.2\jackson-annotations-2.17.2.jar",
    "$repo\com\fasterxml\jackson\core\jackson-core\2.17.2\jackson-core-2.17.2.jar",
    "$repo\com\fasterxml\jackson\core\jackson-databind\2.17.2\jackson-databind-2.17.2.jar",
    "$repo\com\fasterxml\jackson\datatype\jackson-datatype-jsr310\2.17.2\jackson-datatype-jsr310-2.17.2.jar",
    "$repo\com\formdev\flatlaf\3.5.1\flatlaf-3.5.1.jar",
    "$repo\org\glassfish\jakarta.json\2.0.1\jakarta.json-2.0.1.jar"
)

$missing = $dependencies | Where-Object { -not (Test-Path $_) }
if ($missing.Count -gt 0) {
    throw "Missing dependency jars:`n$($missing -join "`n")`nRun Maven once to populate the local repository, or install Maven and use pom.xml."
}

$buildDir = Join-Path $root "build"
$classesDir = Join-Path $buildDir "classes"
$libsDir = Join-Path $buildDir "package-input\lib"
$packageInputDir = Join-Path $buildDir "package-input"
$distDir = Join-Path $root "dist"
$mainJar = Join-Path $packageInputDir "$appName.jar"

Remove-Item -Recurse -Force $buildDir, (Join-Path $distDir $appName) -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $classesDir, $libsDir, $packageInputDir, $distDir | Out-Null

$sources = Get-ChildItem (Join-Path $root "src\main\java") -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
$classpath = ($dependencies -join [IO.Path]::PathSeparator)

& $javac --release 21 -encoding UTF-8 -cp $classpath -d $classesDir $sources
if ($LASTEXITCODE -ne 0) {
    throw "javac failed with exit code $LASTEXITCODE"
}

foreach ($dependency in $dependencies) {
    Copy-Item $dependency $libsDir
}

$classPathEntries = Get-ChildItem $libsDir -Filter "*.jar" |
    Sort-Object Name |
    ForEach-Object { "lib/$($_.Name)" }

$manifestPath = Join-Path $buildDir "MANIFEST.MF"
@(
    "Manifest-Version: 1.0",
    "Main-Class: com.videograb.Main",
    "Class-Path: $($classPathEntries -join ' ')",
    ""
) | Set-Content -Encoding ASCII $manifestPath

& $jar cfm $mainJar $manifestPath -C $classesDir .
if ($LASTEXITCODE -ne 0) {
    throw "jar failed with exit code $LASTEXITCODE"
}

$toolsDir = Join-Path $root "tools"
if (Test-Path $toolsDir) {
    Copy-Item $toolsDir (Join-Path $packageInputDir "tools") -Recurse -Force
}

& $jpackage `
    --type app-image `
    --name $appName `
    --app-version $version `
    --vendor "VideoGrab" `
    --dest $distDir `
    --input $packageInputDir `
    --main-jar "$appName.jar" `
    --main-class com.videograb.Main `
    --java-options "-Dfile.encoding=UTF-8" `
    --java-options "-Dflatlaf.useNativeLibrary=false"

if ($LASTEXITCODE -ne 0) {
    throw "jpackage failed with exit code $LASTEXITCODE"
}

$exePath = Join-Path $distDir "$appName\$appName.exe"
Write-Host "Built $exePath"
