package com.lapissea.jorth;

import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.type.ClassType;
import com.lapissea.jorth.lang.type.GenericType;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class JorthDebugNamesTests{
	private record Local(String name, String descriptor, String signature, int slot, int start, int end){ }
	
	private static List<Local> locals(byte[] bytes, String method){
		var result = new ArrayList<Local>();
		new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9){
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions){
				if(!name.equals(method)) return null;
				return new MethodVisitor(Opcodes.ASM9){
					private final IdentityHashMap<Label, Integer> labels = new IdentityHashMap<>();
					@Override
					public void visitLabel(Label label){ labels.put(label, labels.size()); }
					@Override
					public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index){
						result.add(new Local(name, descriptor, signature, index, labels.get(start), labels.get(end)));
					}
				};
			}
		}, 0);
		return result;
	}
	
	@Test
	public void parameterNamesAreAvailableThroughReflection() throws Exception{
		var cls = TestUtils.generateAndLoadInstanceSimple("test.DebugParameters", cd -> {
			cd.function("run").staticAcc().arg(long.class, "count").arg(String.class, "message").body();
			cd.function("instance").arg(double.class, "value").body();
		});
		var parameters = cls.getMethod("run", long.class, String.class).getParameters();
		assertThat(parameters).allMatch(p -> p.isNamePresent());
		assertThat(parameters).extracting(p -> p.getName()).containsExactly("count", "message");
		assertThat(cls.getMethod("instance", double.class).getParameters()[0].getName()).isEqualTo("value");
	}
	
	@Test
	public void abstractAndEnumParameters() throws Exception{
		var api = TestUtils.generateAndLoadInstanceSimple("test.DebugInterface", cd -> {
			cd.type(ClassType.INTERFACE);
			cd.function("accept").arg(String.class, "message");
		});
		assertThat(api.getMethod("accept", String.class).getParameters()[0].getName()).isEqualTo("message");
		var enumeration = TestUtils.generateAndLoadInstanceSimple("test.DebugEnum", cd -> {
			cd.type(ClassType.ENUM);
			cd.instanceInit().arg(int.class, "code").body();
			cd.enumConstant("ONE", c -> c.val(1));
		});
		var parameters = enumeration.getDeclaredConstructor(String.class, int.class, int.class).getParameters();
		assertThat(parameters).extracting(p -> p.getName()).containsExactly("€enum€name", "€enum€ordinal", "code");
		assertThat(parameters[0].isSynthetic()).isTrue();
		assertThat(parameters[1].isSynthetic()).isTrue();
		assertThat(parameters[2].isSynthetic()).isFalse();
	}
	
	@Test
	public void localScopesSurviveSlotReuseAndLazyBlocks() throws Exception{
		var cd   = new ClassDefinition(null).name(ClassName.dotted("test.DebugLocals"));
		var body = cd.function("run").arg(long.class, "count").body();
		body.val(1L).setIntoNewVar("first").get("first").pop().forgetVar("first");
		body.scope(c -> c.val(2.0).setIntoNewVar("nested").get("nested").pop());
		body.lazyBlock(c -> c.val("lazy").setIntoNewVar("deferred").get("deferred").pop());
		body.var(GenericType.of(List.class).withArgs(String.class), "last").nullVal(List.class).set("last");
		body.get("last").pop();
		var bytes  = cd.getClassFile();
		var locals = locals(bytes, "run");
		assertThat(locals).extracting(Local::name).containsExactlyInAnyOrder("this", "count", "first", "nested", "deferred", "last");
		var ordered = List.of("first", "nested", "deferred", "last").stream()
		                  .map(name -> locals.stream().filter(l -> l.name.equals(name)).findFirst().orElseThrow()).toList();
		assertThat(ordered).extracting(Local::slot).containsExactly(3, 3, 3, 3);
		for(int i = 1; i<ordered.size(); i++) assertThat(ordered.get(i - 1).end).isLessThanOrEqualTo(ordered.get(i).start);
		assertThat(ordered.getLast().signature).isEqualTo("Ljava/util/List<Ljava/lang/String;>;");
		assertThat(locals).allMatch(l -> l.start<l.end);
		// Loading also checks that the JVM accepts all emitted debug attributes.
		var cls = new ClassLoader(getClass().getClassLoader()){
			Class<?> load(){ return defineClass(null, bytes, 0, bytes.length); }
		}.load();
		cls.getMethod("run", long.class).invoke(cls.getConstructor().newInstance(), 4L);
		assertThat(locals(bytes, "<init>")).extracting(Local::name).containsExactly("this");
	}
}
