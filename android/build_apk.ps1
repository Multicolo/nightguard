param(
  [string]$Sdk = "",
  [string]$Jdk = "",
  [string]$Name = "NightGuard"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

# ---- 找 SDK（--Sdk 优先，其次 ANDROID_HOME / ANDROID_SDK_ROOT / 默认安装位置）
if (-not $Sdk) { $Sdk = $env:ANDROID_HOME }
if (-not $Sdk) { $Sdk = $env:ANDROID_SDK_ROOT }
if (-not $Sdk) { $Sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
if (-not (Test-Path (Join-Path $Sdk "platforms\android-34\android.jar"))) {
  throw "找不到 Android SDK（需要 platform android-34）：$Sdk；用 -Sdk D:\Android\Sdk 指定"
}

# ---- 找 JDK 17（--Jdk 优先，其次 JAVA_HOME）
if (-not $Jdk) { $Jdk = $env:JAVA_HOME }
if (-not $Jdk -or -not (Test-Path (Join-Path $Jdk "bin\javac.exe"))) {
  throw "找不到 JDK 17；设置 JAVA_HOME 或用 -Jdk 指定"
}

# ---- 找 Python（inject_dex.py 用）
$py = "python"
if (-not (Get-Command $py -ErrorAction SilentlyContinue)) {
  if (Get-Command py -ErrorAction SilentlyContinue) { $py = "py" } else { throw "需要 Python 3（inject_dex.py）" }
}

$bt = Join-Path $Sdk "build-tools\34.0.0"
$platform = Join-Path $Sdk "platforms\android-34\android.jar"
$src = Join-Path $root "java"
$build = Join-Path $root "build"
$outDir = Join-Path $root "dist"
New-Item -ItemType Directory -Force $build, $outDir | Out-Null
$obj = Join-Path $build "obj"
$dex = Join-Path $build "dex"
New-Item -ItemType Directory -Force $obj, $dex | Out-Null

$env:JAVA_HOME = $Jdk
$env:PATH = "$Jdk\bin;$env:PATH"

# ---- 签名用 keystore（首次构建自动生成，仅供自用/测试；正式发布请换自己的）
$ks = Join-Path $root "nightguard.jks"
if (-not (Test-Path $ks)) {
  Write-Host "[key] 生成签名用 keystore: nightguard.jks (密码 nightguard，请自行更换)"
  & "$Jdk\bin\keytool.exe" -genkeypair -v -keystore $ks -alias nightguard `
    -keyalg RSA -keysize 2048 -validity 10000 `
    -storepass nightguard -keypass nightguard `
    -dname "CN=NightGuard, OU=NightGuard, O=NightGuard, C=CN" | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

Write-Host "[0/6] aapt2 compile res"
& "$bt\aapt2.exe" compile --dir "$root\res" -o "$build\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "[1/6] aapt2 link"
& "$bt\aapt2.exe" link -o "$build\base.apk" -I $platform -R "$build\res.zip" `
  --manifest "$root\AndroidManifest.xml" --min-sdk-version 24 --target-sdk-version 34 `
  --auto-add-overlay --java "$build\gen"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[2/6] javac"
$sources = @(Get-ChildItem $src -Recurse -Filter *.java | ForEach-Object { $_.FullName }) + @(Get-ChildItem "$build\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& "$Jdk\bin\javac.exe" -source 8 -target 8 -encoding UTF-8 -bootclasspath $platform -d $obj $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "[3/6] d8"
$classes = Get-ChildItem $obj -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& "$bt\d8.bat" --lib $platform --min-api 24 --output $dex $classes
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "[4/6] add classes.dex"
& $py "$root\inject_dex.py" "$build\base.apk" "$dex\classes.dex" "$build\unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw "inject dex failed" }

Write-Host "[5/6] zipalign"
& "$bt\zipalign.exe" -f 4 "$build\unsigned.apk" "$build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "[6/6] sign"
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:nightguard --key-pass pass:nightguard `
  --out "$outDir\$Name.apk" "$build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host "APK OK:" (Get-Item "$outDir\$Name.apk").Length "bytes -> $outDir\$Name.apk"
