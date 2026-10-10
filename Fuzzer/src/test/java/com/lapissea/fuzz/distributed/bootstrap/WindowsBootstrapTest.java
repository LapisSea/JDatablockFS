package com.lapissea.fuzz.distributed.bootstrap;

import com.lapissea.fuzz.distributed.FileCleanup;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_16LE;
import static org.testng.Assert.*;

/**
 * Covers the PowerShell side of Windows helper support: the encoded-command transport, the
 * cold-cache bootstrap scripts, and the spec-file indirection for long invocations.
 *
 * <p>Shape tests run everywhere. Behavior tests execute the generated scripts through a local
 * PowerShell ({@code pwsh} or {@code powershell}); the scripts are written with {@code Join-Path}
 * and {@code [IO.Path]::DirectorySeparatorChar} so they run unmodified on Linux PowerShell. When
 * no PowerShell installation exists the behavior tests skip.
 */
public class WindowsBootstrapTest{
	
	@Test
	public void remoteInvocationQuotesPosixStyle(){
		assertEquals(PosixBootstrap.INSTANCE.remoteInvocation(UUID.randomUUID(), List.of("java", "-cp", "x y"), NO_TRANSFER), "'java' '-cp' 'x y'");
	}
	
	@Test
	public void interpretInvokesDirectlyAndPropagatesExitCode() throws IOException, InterruptedException{
		var script = decode(WindowsBootstrap.INSTANCE.remoteInvocation(UUID.randomUUID(), List.of("C:\\Program Files\\java.exe", "-cp", "C:\\a.jar;C:\\b.jar", "Main", "it's"), NO_TRANSFER));
		
		assertTrue(script.contains("& 'C:\\Program Files\\java.exe' '-cp' 'C:\\a.jar;C:\\b.jar' 'Main' 'it''s'"), script);
		assertTrue(script.endsWith("exit $LASTEXITCODE"), script);
	}
	
	@Test
	public void longInvocationSpecIsSessionScopedAndCleanedByFailed() throws Exception{
		var session  = UUID.randomUUID();
		var captured = new ArrayList<String>();
		HostBootstrap.PayloadTransfer transfer = (command, payload) -> captured.add(decode(command));
		
		WindowsBootstrap.INSTANCE.remoteInvocation(session, List.of("java", "-cp", "x".repeat(40_000)), transfer);
		assertEquals(captured.size(), 1);
		assertTrue(captured.getFirst().contains(".fuzz-spec-" + session + ".txt"), captured.getFirst());
		
		// A failed launch must remove that same spec file, not just the reservation and upload tree.
		var failed = decode(WindowsBootstrap.INSTANCE.failed("C:\\cache", session).command());
		assertTrue(failed.contains(".fuzz-spec-" + session + ".txt"), failed);
	}
	
	@Test
	public void prepareCarriesReservationAndProbe(){
		var session = UUID.randomUUID();
		var script  = decode(WindowsBootstrap.INSTANCE.prepare("C:\\fuzz cache", "C:\\runs", session, List.of()).command());
		
		assertTrue(script.contains("$cache = 'C:\\fuzz cache'"), script);
		assertTrue(script.contains(".preparing"), script);
		assertTrue(script.contains(session.toString()), script);
		assertTrue(script.contains(RemoteWorkspace.MARKER), script);
		assertTrue(script.contains("ReadLine()"), script);
		assertTrue(script.contains("Unmanaged fuzzer artifact directory"), script);
	}
	
	@Test
	public void uploadExtractsTarAndGuardsMarkers(){
		var session = UUID.randomUUID();
		var script  = decode(WindowsBootstrap.INSTANCE.upload("C:\\fuzz", session, List.of()).command());
		
		assertTrue(script.contains("tar -xf - -C $staging"), script);
		assertTrue(script.contains(".upload-" + session), script);
		assertTrue(script.contains("Unmanaged fuzzer artifact directory"), script);
		assertTrue(script.contains("$stagingMarker"), script);
		assertTrue(script.contains("Remove-Item -LiteralPath $staging -Recurse -Force"), script);
	}
	
	@Test
	public void specRunnerReadsStreamedInvocationThenDeletesIt(){
		var script = decode(WindowsBootstrap.specRunner("spec file.txt"));
		
		assertTrue(script.contains("[IO.File]::ReadAllLines('spec file.txt', [Text.Encoding]::UTF8)"), script);
		assertTrue(script.contains("Remove-Item -LiteralPath 'spec file.txt' -Force"), script);
		assertTrue(script.contains("& $exe $rest"), script);
		assertTrue(script.stripTrailing().endsWith("exit $LASTEXITCODE"), script);
	}
	
	@Test
	public void stdinWriterCopiesRawStdinToFile(){
		var script = decode(WindowsBootstrap.stdinWriter("spec file.txt"));
		
		// Streams straight to the file: a MemoryStream receiver stalls for inputs over a few KB on the
		// Windows OpenSSH channel.
		assertTrue(script.contains("[IO.File]::OpenWrite('spec file.txt')"), script);
		assertTrue(script.contains("[Console]::OpenStandardInput().CopyTo($out)"), script);
	}
	
	@Test(timeOut = 120_000)
	public void directInvocationStreamsStdinToChild() throws Exception{
		var script = decodeScript(WindowsBootstrap.INSTANCE.remoteInvocation(UUID.randomUUID(), List.of("/bin/cat"), NO_TRANSFER));
		var result = runScript(script, "hello".getBytes(StandardCharsets.UTF_8));
		
		assertEquals(result.exit(), 0, result.stderr());
		assertEquals(result.stdout(), "hello");
	}
	
	@Test(timeOut = 120_000)
	public void prepareReservesCleansAndProbes() throws Exception{
		var root = Files.createTempDirectory("win-bootstrap-prepare-");
		try{
			var cache     = Files.createDirectory(root.resolve("cache"));
			var parent    = root.resolve("work directory");
			var hash      = "a".repeat(64);
			var directory = Files.createDirectory(cache.resolve(hash));
			Files.writeString(directory.resolve(".fuzzer-managed"), RemoteWorkspace.MARKER);
			Files.writeString(directory.resolve("cached.jar"), "keep");
			var session = UUID.randomUUID();
			var names   = List.of(hash + "/cached.jar", "b".repeat(64) + "/missing.jar");
			
			var phase  = WindowsBootstrap.INSTANCE.prepare(cache.toString(), parent.toString(), session, names);
			var result = runScript(decodeScript(phase.command()), stdinLines(phase.stdinLines()));
			assertEquals(result.exit(), 0, result.stderr());
			
			var lines = result.stdout().lines().toList();
			assertEquals(lines.size(), 3, result.stdout());
			assertEquals(Path.of(lines.get(0)).toRealPath(), cache.toRealPath());
			assertEquals(Path.of(lines.get(1)).toRealPath(), parent.toRealPath());
			assertEquals(lines.get(2), "1");
			assertTrue(Files.exists(cache.resolve(".preparing/" + session)));
			assertTrue(Files.isDirectory(parent));
		}finally{
			FileCleanup.deleteTree(root);
		}
	}
	
	@Test(timeOut = 120_000)
	public void prepareRejectsUnmanagedDirectory() throws Exception{
		var root = Files.createTempDirectory("win-bootstrap-unmanaged-");
		try{
			var cache     = Files.createDirectory(root.resolve("cache"));
			var directory = Files.createDirectory(cache.resolve("c".repeat(64)));
			var file      = Files.writeString(directory.resolve("user-file.jar"), "user");
			
			var phase  = WindowsBootstrap.INSTANCE.prepare(cache.toString(), root.resolve("runs").toString(), UUID.randomUUID(),
			                                               List.of("c".repeat(64) + "/dependency.jar"));
			var result = runScript(decodeScript(phase.command()), stdinLines(phase.stdinLines()));
			
			assertNotEquals(result.exit(), 0);
			assertTrue(result.stderr().contains("Unmanaged fuzzer artifact directory"), result.stderr());
			assertEquals(Files.readString(file), "user");
		}finally{
			FileCleanup.deleteTree(root);
		}
	}
	
	@Test(timeOut = 120_000)
	public void uploadPublishesTarEntries() throws Exception{
		var root = Files.createTempDirectory("win-bootstrap-upload-");
		try{
			var cache  = Files.createDirectory(root.resolve("cache"));
			var source = Files.writeString(root.resolve("input.jar"), "artifact");
			var name   = "a".repeat(64) + "/dependency jar.jar";
			var archive = new ByteArrayOutputStream();
			TarIO.write(archive, List.of(new RuntimeBundle.Artifact(name, source)));
			
			var session = UUID.randomUUID();
			var result  = runScript(decodeScript(WindowsBootstrap.INSTANCE.upload(cache.toString(), session, List.of()).command()), archive.toByteArray());
			assertEquals(result.exit(), 0, result.stderr());
			
			var published = cache.resolve(name);
			assertTrue(Files.isRegularFile(published));
			assertEquals(Files.readString(published), "artifact");
			assertEquals(Files.readString(cache.resolve("a".repeat(64)).resolve(".fuzzer-managed")), RemoteWorkspace.MARKER);
			assertTrue(Files.getLastModifiedTime(published).toMillis() > System.currentTimeMillis() - 60_000);
			assertFalse(Files.exists(cache.resolve(".fuzzer-managed")));
			assertFalse(Files.exists(cache.resolve(".upload-" + session)));
		}finally{
			FileCleanup.deleteTree(root);
		}
	}
	
	@Test(timeOut = 120_000)
	public void uploadRejectsUnmanagedDirectory() throws Exception{
		var root = Files.createTempDirectory("win-bootstrap-upload-reject-");
		try{
			var cache = Files.createDirectory(root.resolve("cache"));
			var name  = "c".repeat(64) + "/dependency.jar";
			Files.createDirectory(cache.resolve("c".repeat(64)));
			var file   = Files.writeString(cache.resolve(name), "user data");
			var source = Files.writeString(root.resolve("input.jar"), "new data");
			var archive = new ByteArrayOutputStream();
			TarIO.write(archive, List.of(new RuntimeBundle.Artifact(name, source)));
			
			var result = runScript(decodeScript(WindowsBootstrap.INSTANCE.upload(cache.toString(), UUID.randomUUID(), List.of()).command()), archive.toByteArray());
			
			assertNotEquals(result.exit(), 0);
			assertTrue(result.stderr().contains("Unmanaged fuzzer artifact directory"), result.stderr());
			assertEquals(Files.readString(file), "user data");
		}finally{
			FileCleanup.deleteTree(root);
		}
	}
	
	@Test(timeOut = 120_000)
	public void failedRemovesOnlyItsOwnReservation() throws Exception{
		var root = Files.createTempDirectory("win-bootstrap-failed-");
		try{
			var cache = Files.createDirectory(root.resolve("cache"));
			Files.createDirectory(cache.resolve(".preparing"));
			var session = UUID.randomUUID();
			var other   = UUID.randomUUID();
			for(var id : List.of(session, other)){
				Files.writeString(cache.resolve(".preparing/" + id), "");
				Files.createDirectory(cache.resolve(".upload-" + id));
				Files.writeString(cache.resolve(".upload-" + id + "/partial"), "partial");
			}
			var sentinel = Files.writeString(cache.resolve("user-data"), "keep");
			
			var result = runScript(decodeScript(WindowsBootstrap.INSTANCE.failed(cache.toString(), session).command()), new byte[0]);
			assertEquals(result.exit(), 0, result.stderr());
			
			assertFalse(Files.exists(cache.resolve(".preparing/" + session)));
			assertFalse(Files.exists(cache.resolve(".upload-" + session)));
			assertTrue(Files.exists(cache.resolve(".preparing/" + other)));
			assertEquals(Files.readString(cache.resolve(".upload-" + other + "/partial")), "partial");
			assertEquals(Files.readString(sentinel), "keep");
		}finally{
			FileCleanup.deleteTree(root);
		}
	}
	
	@Test(timeOut = 120_000)
	public void specTransportRoundTripsInvocationStdinAndExitCode() throws Exception{
		var root = Files.createTempDirectory("win-bootstrap-spec-");
		try{
			var spec = root.resolve("spec.txt");
			var payload = String.join("\n", "/bin/cat").getBytes(StandardCharsets.UTF_8);
			var written = runScript(decodeScript(WindowsBootstrap.stdinWriter(spec.toString())), payload);
			assertEquals(written.exit(), 0, written.stderr());
			assertEquals(Files.readString(spec), "/bin/cat");
			
			var echoed = runScript(decodeScript(WindowsBootstrap.specRunner(spec.toString())), "hello".getBytes(StandardCharsets.UTF_8));
			assertEquals(echoed.exit(), 0, echoed.stderr());
			assertEquals(echoed.stdout(), "hello");
			assertFalse(Files.exists(spec));
			
			Files.writeString(spec, String.join("\n", "/bin/sh", "-c", "exit 3"));
			var failed = runScript(decodeScript(WindowsBootstrap.specRunner(spec.toString())), new byte[0]);
			assertEquals(failed.exit(), 3, failed.stderr());
		}finally{
			FileCleanup.deleteTree(root);
		}
	}
	
	private static byte[] stdinLines(List<String> lines){
		var builder = new StringBuilder();
		for(var line : lines){
			builder.append(line).append('\n');
		}
		return builder.toString().getBytes(StandardCharsets.UTF_8);
	}
	
	private static final String COMMAND_PREFIX = "powershell -NoProfile -NonInteractive -EncodedCommand ";
	
	/** None of the commands built in these tests may need the out-of-band transport. */
	private static final HostBootstrap.PayloadTransfer NO_TRANSFER = (command, payload) -> fail("Unexpected out-of-band transfer: " + command);
	
	private static String decode(String command){
		assertTrue(command.startsWith(COMMAND_PREFIX), command);
		return decodeScript(command);
	}
	private static String decodeScript(String command){
		return new String(Base64.getDecoder().decode(command.substring(COMMAND_PREFIX.length())), UTF_16LE);
	}
	
	private record Result(int exit, String stdout, String stderr){ }
	
	private static Result runScript(String script, byte[] input) throws Exception{
		var pwsh = requirePowerShell();
		var process = new ProcessBuilder(pwsh, "-NoProfile", "-NonInteractive", "-EncodedCommand",
		                                 Base64.getEncoder().encodeToString(script.getBytes(UTF_16LE)))
			.start();
		try(OutputStream in = process.getOutputStream()){
			in.write(input);
		}
		
		var stdout = readAll(process.getInputStream());
		var stderr = readAll(process.getErrorStream());
		assertTrue(process.waitFor(90, TimeUnit.SECONDS), "PowerShell did not terminate");
		return new Result(process.exitValue(), stdout, stderr);
	}
	private static String readAll(InputStream stream) throws Exception{
		return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
	}
	
	private static String probedPwsh;
	private static synchronized String requirePowerShell(){
		if(probedPwsh == null){
			for(var candidate : List.of("pwsh", "powershell")){
				try{
					var test = new ProcessBuilder(candidate, "-NoProfile", "-NonInteractive", "-Command", "exit 0")
						.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
						.start();
					if(test.waitFor(30, TimeUnit.SECONDS)){
						probedPwsh = candidate;
						break;
					}
				}catch(Exception|ThreadDeath ignored){ }
			}
		}
		if(probedPwsh == null) throw new SkipException("No PowerShell installation found");
		return probedPwsh;
	}
}
