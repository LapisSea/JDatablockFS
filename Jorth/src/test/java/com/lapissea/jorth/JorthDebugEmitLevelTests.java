package com.lapissea.jorth;

import com.lapissea.jorth.lang.ClassName;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.testng.annotations.Test;

import static com.lapissea.jorth.ClassDefinition.DebugEmitLevel.LIMITED;
import static com.lapissea.jorth.ClassDefinition.DebugEmitLevel.NONE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class JorthDebugEmitLevelTests{
	@Test
	public void propertyIsReadForEachConstruction(){
		String property = "jorth.debugEmitLevel";
		String previous = System.getProperty(property);
		try{
			System.clearProperty(property);
			var original     = new ClassDefinition(null);
			var defaultLevel = ClassDefinition.class.desiredAssertionStatus()? LIMITED : NONE;
			assertThat(original.debugEmitLevel()).isEqualTo(defaultLevel);
			System.setProperty(property, "NONE");
			assertThat(new ClassDefinition(null).debugEmitLevel()).isEqualTo(NONE);
			assertThat(ClassDefinition.hiddenNestmate(getClass()).debugEmitLevel()).isEqualTo(NONE);
			assertThat(original.debugEmitLevel()).isEqualTo(defaultLevel);
			assertThat(original.debugEmitLevel(NONE)).isSameAs(original);
			assertThat(original.debugEmitLevel()).isEqualTo(NONE);
			System.setProperty(property, "LIMITED");
			assertThat(new ClassDefinition(null).debugEmitLevel()).isEqualTo(LIMITED);
			System.setProperty(property, "invalid");
			assertThat(new ClassDefinition(null).debugEmitLevel()).isEqualTo(defaultLevel);
			assertThatThrownBy(() -> original.debugEmitLevel(null)).isInstanceOf(NullPointerException.class);
		}finally{
			if(previous == null) System.clearProperty(property);
			else System.setProperty(property, previous);
		}
	}
	
	private static int[] metadataCounts(byte[] bytes){
		var counts = new int[3];
		new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9){
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions){
				return new MethodVisitor(Opcodes.ASM9){
					@Override
					public void visitParameter(String name, int access){ counts[0]++; }
					@Override
					public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index){ counts[1]++; }
					@Override
					public void visitLineNumber(int line, Label start){ counts[2]++; }
				};
			}
		}, 0);
		return counts;
	}
	
	@Test
	public void setterControlsAllDebugMetadataAtEmission() throws Exception{
		var cd = new ClassDefinition(null).name(ClassName.dotted("test.DebugEmitLevel"));
		cd.debugEmitLevel(NONE);
		var body = cd.function("run").staticAcc().arg(int.class, "input").returns(int.class).body();
		body.scope(c -> c.val(2).setIntoNewVar("nested").get("nested").pop());
		body.lazyBlock(c -> c.val(3).setIntoNewVar("lazy").get("lazy").pop());
		body.get("input").add(1);
		// The level may be overridden even after the body has been built.
		cd.debugEmitLevel(LIMITED);
		for(int count : metadataCounts(cd.getClassFile())) assertThat(count).isPositive();
		cd.debugEmitLevel(NONE);
		var bytes = cd.getClassFile();
		assertThat(metadataCounts(bytes)).containsExactly(0, 0, 0);
		var cls = new ClassLoader(getClass().getClassLoader()){
			Class<?> load(){ return defineClass(null, bytes, 0, bytes.length); }
		}.load();
		assertThat(cls.getMethod("run", int.class).invoke(null, 4)).isEqualTo(5);
		assertThat(cls.getMethod("run", int.class).getParameters()[0].isNamePresent()).isFalse();
	}
}
