package com.lapissea.dfs.tests;

import com.lapissea.dfs.internal.Access;
import org.testng.SkipException;
import org.testng.annotations.Test;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end coverage for the declared-consent path: a fresh named module that only declares
 * {@code provides AccessConsent} must be consented through ServiceLoader. The provider is deliberately
 * not named DFSAccessConsent, so the convention walk cannot grant consent.
 */
public class AccessConsentDeclaredTests{
	
	@Test
	void declaredProviderGrantsConsent() throws Exception{
		var layer  = buildLayer("consentdeclared", "Provider", false);
		var target = layer.findLoader("consentdeclared").loadClass("consentdeclared.Target");
		var field  = target.getDeclaredField("value");
		
		assertThat(Access.makeVarHandle(field)).isNotNull();
	}
	
	@Test
	void declaredUnsafeProviderIsRejected() throws Exception{
		var layer  = buildLayer("consentdeclaredbad", "BadProvider", true);
		var target = layer.findLoader("consentdeclaredbad").loadClass("consentdeclaredbad.Target");
		var field  = target.getDeclaredField("value");
		
		assertThatThrownBy(() -> Access.makeVarHandle(field)).isInstanceOf(IllegalAccessException.class);
	}
	
	private static ModuleLayer buildLayer(String moduleName, String providerName, boolean leak) throws Exception{
		var modulePath = System.getProperty("jdk.module.path", "");
		if(modulePath.isBlank()) throw new SkipException("Not running on the module path");
		var compiler = ToolProvider.getSystemJavaCompiler();
		if(compiler == null) throw new SkipException("No system java compiler");
		
		var src = Files.createTempDirectory(moduleName + "-src");
		var out = Files.createTempDirectory(moduleName + "-out");
		var pkg = src.resolve(moduleName);
		Files.createDirectories(pkg);
		
		Files.writeString(
			src.resolve("module-info.java"),
			"module " + moduleName + "{\n" +
			"    requires JDatablockFS.core;\n" +
			"    exports " + moduleName + ";\n" +
			"    provides com.lapissea.dfs.access.AccessConsent with " + moduleName + "." + providerName + ";\n" +
			"}\n");
		
		Files.writeString(
			pkg.resolve(providerName + ".java"),
			"package " + moduleName + ";\n" +
			"import com.lapissea.dfs.access.AccessConsent;\n" +
			"import com.lapissea.dfs.type.IOInstance;\n" +
			"import java.lang.invoke.MethodHandles;\n" +
			"public final class " + providerName + " implements AccessConsent{\n" +
			(leak? "    public static final Object LEAK = new Object();\n" : "") +
			"    static{ IOInstance.allowFullAccess(MethodHandles.lookup()); }\n" +
			"}\n");
		
		Files.writeString(
			pkg.resolve("Target.java"),
			"package " + moduleName + ";\n" +
			"public class Target{\n" +
			"    private int value;\n" +
			"}\n");
		
		var args = new ArrayList<>(List.of(
			"--module-path", modulePath,
			"-d", out.toString(),
			src.resolve("module-info.java").toString(),
			pkg.resolve(providerName + ".java").toString(),
			pkg.resolve("Target.java").toString()
		));
		var err = new ByteArrayOutputStream();
		if(compiler.run(null, null, err, args.toArray(String[]::new)) != 0){
			throw new IllegalStateException("Failed to compile " + moduleName + ":\n" + err.toString(StandardCharsets.UTF_8));
		}
		
		var boot   = ModuleLayer.boot();
		var config = Configuration.resolve(ModuleFinder.of(), List.of(boot.configuration()), ModuleFinder.of(out), List.of(moduleName));
		return ModuleLayer.defineModulesWithOneLoader(config, List.of(boot), ClassLoader.getSystemClassLoader()).layer();
	}
}
