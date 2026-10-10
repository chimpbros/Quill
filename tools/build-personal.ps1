param([switch]$SignedRelease)
$ErrorActionPreference='Stop'
$taskRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location -LiteralPath $taskRoot
if(-not $env:JAVA_HOME) {
 $taskJava=Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools/java') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
 if($taskJava) { $env:JAVA_HOME=$taskJava.FullName } else { throw 'Set JAVA_HOME to a JDK 21 installation.' }
}
$env:GRADLE_USER_HOME=Join-Path $taskRoot '.tools/gradle'
$env:ANDROID_USER_HOME=Join-Path $taskRoot '.tools/android-user'
if($SignedRelease) {
 $taskSigning=Join-Path $taskRoot '.private/signing'
 New-Item -ItemType Directory -Force -Path $taskSigning | Out-Null
 $taskPasswordPath=Join-Path $taskSigning 'password.clixml'
 if(-not (Test-Path -LiteralPath $taskPasswordPath)) {
  $taskPassword=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
  ConvertTo-SecureString $taskPassword -AsPlainText -Force | Export-Clixml -LiteralPath $taskPasswordPath
 } else {
  $taskSecure=Import-Clixml -LiteralPath $taskPasswordPath
  $taskPointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($taskSecure)
  try { $taskPassword=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($taskPointer) }
  finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($taskPointer) }
 }
 $env:QUILL_SIGNING_STORE_FILE=Join-Path $taskSigning 'quill-personal.p12'
 $env:QUILL_SIGNING_STORE_PASSWORD=$taskPassword
 $env:QUILL_SIGNING_KEY_PASSWORD=$taskPassword
 try {
  if(-not (Test-Path -LiteralPath $env:QUILL_SIGNING_STORE_FILE)) {
   & (Join-Path $env:JAVA_HOME 'bin/keytool.exe') -genkeypair -alias personal -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Quill Personal' -storetype PKCS12 -keystore $env:QUILL_SIGNING_STORE_FILE -storepass:env QUILL_SIGNING_STORE_PASSWORD -keypass:env QUILL_SIGNING_KEY_PASSWORD
   if($LASTEXITCODE -ne 0) { throw 'Signing key generation failed.' }
  }
  & ./gradlew.bat :app:assembleRelease --max-workers=2 --console=plain
  if($LASTEXITCODE -ne 0) { throw 'Release build failed.' }
  New-Item -ItemType Directory -Force -Path artifacts | Out-Null
  Copy-Item -LiteralPath 'app/build/outputs/apk/release/app-release.apk' -Destination 'artifacts/quill-personal.apk'
 } finally {
  Remove-Item Env:QUILL_SIGNING_STORE_PASSWORD,Env:QUILL_SIGNING_KEY_PASSWORD -ErrorAction SilentlyContinue
  $taskPassword=$null
 }
} else {
 & ./gradlew.bat :app:assembleDebug :aira:testDebugUnitTest :app:lintDebug --max-workers=2 --console=plain
 if($LASTEXITCODE -ne 0) { throw 'Build or validation failed.' }
}
