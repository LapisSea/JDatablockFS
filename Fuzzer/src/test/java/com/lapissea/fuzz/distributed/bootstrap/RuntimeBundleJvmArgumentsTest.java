package com.lapissea.fuzz.distributed.bootstrap;

import com.lapissea.fuzz.distributed.FileCleanup;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

import static org.testng.Assert.*;

public class RuntimeBundleJvmArgumentsTest{
	@DataProvider
	public Object[][] patchForms(){ return new Object[][]{{false}, {true}}; }
	
	public static class Probe{
		public static void main(String[] args) throws Exception{
			try(var bundle = RuntimeBundle.capture()){
				var argument = bundle.jvmArguments().stream().filter(a -> a.startsWith("--patch-module=java.base="))
				                     .findFirst().orElseThrow(() -> new AssertionError("Patch argument lost"));
				var name = argument.substring("--patch-module=java.base=".length());
				var artifact = bundle.artifacts().stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
				try(var jar = new JarFile(artifact.file().toFile())){
					if(jar.getJarEntry("patch-marker.txt") == null) throw new AssertionError("Patch contents lost");
				}
			}
		}
	}
	
	@Test(dataProvider = "patchForms", timeOut = 30000)
	public void patchModuleInputsAreCapturedInBothArgumentForms(boolean equalsForm) throws Exception{
		var root = Files.createTempDirectory("runtime-patch-test-");
		try{
			var patch = Files.createDirectory(root.resolve("patch"));
			Files.writeString(patch.resolve("patch-marker.txt"), "patch");
			var output = root.resolve("output.txt");
			var classPath = Path.of(RuntimeBundleJvmArgumentsTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()) +
			                File.pathSeparator + Path.of(RuntimeBundle.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString()));
			if(equalsForm) command.add("--patch-module=java.base=" + patch);
			else command.addAll(List.of("--patch-module", "java.base=" + patch));
			command.addAll(List.of("-cp", classPath, Probe.class.getName()));
			var child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
			
			try{
				assertTrue(child.waitFor(20, TimeUnit.SECONDS), "Patch probe timed out");
				assertEquals(child.exitValue(), 0, Files.readString(output));
			}finally{
				if(child.isAlive()){
					child.destroyForcibly();
					child.waitFor(5, TimeUnit.SECONDS);
				}
			}
		}finally{
			FileCleanup.deleteTree(root);
		}
	}
}
