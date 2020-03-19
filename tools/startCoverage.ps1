$locationsList = ring edt locations list
$edtPath = foreach($path in $locationsList){
    if([string]::IsNullOrEmpty($path)) {
        continue;
    };
    if(Test-Path (Join-Path -Path $path -ChildPath "\plugins\com._1c.g5.v8.dt.debug*.jar")) {
        $path;
        break;
    } else {
        continue;
    }
}
if ([string]::IsNullOrEmpty($edtPath)) {
    Write-Error -Message "Could not find EDT location"
    Exit(2)
}
Write-Output "EDT location: $edtPath"

Write-Output "Starting measure..."
$javaArgs = "-cp","`".\*;.\lib\*;$edtPath\plugins\*`"","com.clouds42.Measure1C"
$javaArgs = $javaArgs + $args
start-process "java" -ArgumentList $javaArgs