Add-Type -Assembly 'System.IO.Compression.FileSystem'
$zip = [System.IO.Compression.ZipFile]::OpenRead('d:\data\simultaneous-interpretation\backend-java\target\si-backend-0.1.0-SNAPSHOT.jar')
$entries = $zip.Entries | Where-Object { $_.FullName -like '*asr/*' }
foreach ($e in $entries) { Write-Host $e.FullName }
$zip.Dispose()
