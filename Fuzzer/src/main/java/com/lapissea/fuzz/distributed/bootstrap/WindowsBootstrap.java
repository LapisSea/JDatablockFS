package com.lapissea.fuzz.distributed.bootstrap;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_16LE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

final class WindowsBootstrap implements HostBootstrap{
	
	static final WindowsBootstrap INSTANCE = new WindowsBootstrap();
	
	private WindowsBootstrap(){ }
	
	@Override
	public Script prepare(String cache, String parent, UUID session, List<String> artifactNames){
		return new Script(encode(prepareScript(cache, parent, session)), artifactNames);
	}
	
	@Override
	public Script upload(String cache, UUID session, List<String> artifactNames){
		// The names are taken from the extracted tree, which is what makes the command short.
		return new Script(encode(uploadScript(cache, session)), List.of());
	}
	
	@Override
	public Script failed(String cache, UUID session){
		return new Script(encode(failedScript(cache, session)), List.of());
	}
	
	private static final long POLL_MILLIS = 100;
	
	@Override
	public CacheGate acquireCacheGate(Path cache, long timeoutMillis) throws IOException{
		var channel = FileChannel.open(cache.resolve(".cleanup-lock"), CREATE, WRITE);
		try{
			var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
			while(true){
				FileLock lease;
				try{
					lease = channel.tryLock();
				}catch(OverlappingFileLockException e){
					// Held through another channel of this JVM; treat it as contention.
					lease = null;
				}
				if(lease != null){
					var held = lease;
					if(Thread.currentThread().isInterrupted()){
						held.release();
						throw new IOException("Interrupted acquiring fuzzer cache lock");
					}
					return new CacheGate.Lease(() -> {
						try{
							try{
								held.release();
							}catch(IOException|IllegalStateException ignored){
								// Already released; closing the channel releases the lock as well.
							}
						}finally{
							channel.close();
						}
					});
				}
				if(timeoutMillis == 0){
					channel.close();
					return null;
				}
				if(System.nanoTime()>=deadline){
					throw new IOException("Unable to acquire fuzzer cache lock: " + cache);
				}
				try{
					Thread.sleep(POLL_MILLIS);
				}catch(InterruptedException e){
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted acquiring fuzzer cache lock", e);
				}
			}
		}catch(IOException|RuntimeException e){
			channel.close();
			throw e;
		}
	}
	
	@Override
	public String remoteInvocation(UUID session, List<String> executableAndArgs, PayloadTransfer transfer) throws IOException, InterruptedException{
		var command = interpret(executableAndArgs);
		if(command.length()<=MAX_DIRECT_COMMAND){
			return command;
		}
		// Windows caps whole command lines at 32767 characters; the ssh remote command sits inside a
		// cmd.exe wrapper. Long invocations (the bundle classpath alone can approach the cap) are
		// streamed to a spec file over stdin instead, and a small encoded wrapper reads it back.
		// The name is session-scoped so failed() can remove a spec left behind by a failed launch.
		var spec = ".fuzz-spec-" + session + ".txt";
		transfer.send(stdinWriter(spec), String.join("\n", executableAndArgs).getBytes(UTF_8));
		return specRunner(spec);
	}
	
	// The whole remote command is executed by the Windows default shell (cmd.exe), whose command
	// line is capped at 8191 characters. `command` below is the base64-encoded PowerShell command,
	// so this must stay well under that cap; anything longer goes through the spec file.
	private static final int MAX_DIRECT_COMMAND = 6_000;
	
	/**
	 * Directly invokes an executable with literal arguments, propagating its exit code.
	 */
	static String interpret(List<String> parts){
		var invocation = new StringBuilder("$ErrorActionPreference = 'Stop'\n&");
		for(var part : parts){
			invocation.append(' ').append(psQuote(part));
		}
		invocation.append("\nexit $LASTEXITCODE");
		return encode(invocation.toString());
	}
	
	/**
	 * Runs an invocation whose argument list was streamed to {@code specFile} (one part per line,
	 * UTF-8) by {@link #stdinWriter}, then deletes the spec file.
	 */
	static String specRunner(String specFile){
		return encode(
			"""
				$ErrorActionPreference = 'Stop'
				$parts = [IO.File]::ReadAllLines(%s, [Text.Encoding]::UTF8)
				Remove-Item -LiteralPath %s -Force
				$exe = $parts[0]
				$rest = @()
				if($parts.Count -gt 1){ $rest = $parts[1..($parts.Count - 1)] }
				& $exe $rest
				exit $LASTEXITCODE
				""".formatted(psQuote(specFile), psQuote(specFile)));
	}
	
	static String stdinWriter(String remotePath){
		// Copy straight to the file: PowerShell's CopyTo into a MemoryStream stalls for inputs over a
		// few KB on the Windows OpenSSH channel, whereas a FileStream drains it correctly.
		return encode(
			"""
				$ErrorActionPreference = 'Stop'
				$out = [IO.File]::OpenWrite(%s)
				try{ [Console]::OpenStandardInput().CopyTo($out) } finally { $out.Close() }
				""".formatted(psQuote(remotePath)));
	}
	
	private static String encode(String script){
		return "powershell -NoProfile -NonInteractive -EncodedCommand " + Base64.getEncoder().encodeToString(script.getBytes(UTF_16LE));
	}
	private static String psQuote(String value){ return "'" + value.replace("'", "''") + "'"; }
	
	private static String prepareScript(String cache, String parent, UUID session){
		return PREAMBLE + """
			$cache = %s
			$parent = %s
			New-Item -ItemType Directory -Force -Path $cache | Out-Null
			New-Item -ItemType Directory -Force -Path $parent | Out-Null
			$canonicalCache = (Resolve-Path -LiteralPath $cache).Path
			$canonicalParent = (Resolve-Path -LiteralPath $parent).Path
			$out.WriteLine($canonicalCache)
			$out.WriteLine($canonicalParent)
			$reservations = Join-Path $canonicalCache '.preparing'
			New-Item -ItemType Directory -Force -Path $reservations | Out-Null
			$reservation = Join-Path $reservations '%s'
			if(-not (Test-Path -LiteralPath $reservation)){ New-Item -ItemType File -Path $reservation | Out-Null }
			""".formatted(psQuote(cache), psQuote(parent), session)
		       + MANAGED_DIRECTORY_FUNCTION + PROBE;
	}
	
	private static String uploadScript(String cache, UUID session){
		return PREAMBLE + """
			$cache = %s
			New-Item -ItemType Directory -Force -Path $cache | Out-Null
			$cache = (Resolve-Path -LiteralPath $cache).Path
			$staging = Join-Path $cache '.upload-%s'
			New-Item -ItemType Directory -Force -Path $staging | Out-Null
			[IO.File]::WriteAllText((Join-Path $staging '.fuzzer-managed'), '%s')
			tar -xf - -C $staging
			if($LASTEXITCODE -ne 0){ exit 1 }
			""".formatted(psQuote(cache), session, RemoteWorkspace.MARKER)
		       + MANAGED_DIRECTORY_FUNCTION + """
			       $stagingMarker = Join-Path $staging '.fuzzer-managed'
			       $prefix = $staging.Length + 1
			       Get-ChildItem -LiteralPath $staging -Recurse -File | ForEach-Object {
			       	if($_.FullName -ne $stagingMarker){
			       		$destination = Join-Path $cache $_.FullName.Substring($prefix)
			       		Resolve-ManagedDirectory (Split-Path -Parent $destination) $true
			       		Move-Item -LiteralPath $_.FullName -Destination $destination -Force
			       		(Get-Item -LiteralPath $destination).LastWriteTime = Get-Date
			       	}
			       }
			       Remove-Item -LiteralPath $staging -Recurse -Force
			       """;
	}
	
	private static String failedScript(String cache, UUID session){
		return """
			$ErrorActionPreference = 'SilentlyContinue'
			Remove-Item -LiteralPath (Join-Path (Join-Path %s '.preparing') '%s') -Force
			Remove-Item -LiteralPath (Join-Path %s '.upload-%s') -Recurse -Force
			Remove-Item -LiteralPath '.fuzz-spec-%s.txt' -Force
			exit 0
			""".formatted(psQuote(cache), session, psQuote(cache), session, session);
	}
	
	private static final String PREAMBLE = """
		$ErrorActionPreference = 'Stop'
		$sep = [IO.Path]::DirectorySeparatorChar
		$out = New-Object IO.StreamWriter([Console]::OpenStandardOutput(), (New-Object Text.UTF8Encoding($false)))
		""";
	
	private static final String PROBE = """
		$in = New-Object IO.StreamReader([Console]::OpenStandardInput(), [Text.Encoding]::UTF8)
		$index = 0
		while($null -ne ($name = $in.ReadLine())){
			$relative = $name.Replace('/', $sep)
			Resolve-ManagedDirectory (Join-Path $canonicalCache $relative.Substring(0, $relative.LastIndexOf($sep))) $false
			$file = Join-Path $canonicalCache $relative
			$cached = $false
			if(Test-Path -LiteralPath $file -PathType Leaf){
				$item = Get-Item -LiteralPath $file -Force
				$cached = (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -eq 0)
			}
			if(-not $cached){ $out.WriteLine($index) }
			$index++
		}
		$out.Flush()
		""";
	
	private static final String MANAGED_DIRECTORY_FUNCTION = """
		function Resolve-ManagedDirectory([string]$directory, [bool]$create){
			if(Test-Path -LiteralPath $directory){
				$item = Get-Item -LiteralPath $directory -Force
				$marker = Join-Path $directory '.fuzzer-managed'
				$ok = (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -eq 0) -and $item.PSIsContainer -and
				      (Test-Path -LiteralPath $marker -PathType Leaf) -and
				      (((Get-Item -LiteralPath $marker -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -eq 0) -and
				      ((Get-Content -LiteralPath $marker -Raw).Trim() -eq '%s')
				if(-not $ok){
					$out.Flush()
					[Console]::Error.WriteLine("Unmanaged fuzzer artifact directory: $directory")
					exit 1
				}
				return
			}
			if(-not $create){ return }
			New-Item -ItemType Directory -Path $directory | Out-Null
			[IO.File]::WriteAllText((Join-Path $directory '.fuzzer-managed'), '%s')
		}
		""".formatted(RemoteWorkspace.MARKER, RemoteWorkspace.MARKER);
}
