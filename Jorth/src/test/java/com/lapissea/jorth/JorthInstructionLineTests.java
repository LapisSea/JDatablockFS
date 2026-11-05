package com.lapissea.jorth;

import com.lapissea.jorth.lang.ClassName;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.testng.annotations.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

public class JorthInstructionLineTests{
	private static List<Integer> lines(byte[] bytes, String method){
		var lines = new ArrayList<Integer>();
		new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9){
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions){
				if(!name.equals(method)) return null;
				return new MethodVisitor(Opcodes.ASM9){
					@Override
					public void visitLineNumber(int line, Label start){ lines.add(line); }
				};
			}
		}, 0);
		return lines;
	}
	
	@Test
	public void numbersHighLevelInstructionsAndResetsPerMethod() throws Exception{
		var cd = new ClassDefinition(null).name(ClassName.dotted("test.InstructionLines"));
		cd.function("add").staticAcc().arg(int.class, "value").returns(int.class).body().get("value").add(5);
		cd.function("empty").staticAcc().body();
		var bytes = cd.getClassFile();
		// Increment emits a constant and IADD, but occupies only one Jorth line.
		assertThat(lines(bytes, "add")).containsExactly(1, 2, 3);
		assertThat(lines(bytes, "empty")).containsExactly(1);
		// Automatic constructor: this, super call, lazy initializers, implicit return.
		assertThat(lines(bytes, "<init>")).containsExactly(1, 2, 3, 4);
		assertThat(lines(cd.getClassFile(), "add")).containsExactly(1, 2, 3);
	}
	
	@Test
	public void nestedAndLazyBlocksShareNumbering() throws Exception{
		var cd   = new ClassDefinition(null).name(ClassName.dotted("test.NestedInstructionLines"));
		var body = cd.function("run").staticAcc().body();
		body.val(true).ifTrue(c -> c.val(1).pop()).elseRun(c -> c.val(2).pop());
		body.scope(c -> c.val(3).pop());
		body.lazyBlock(c -> c.val(4).pop());
		body.loop(c -> c.val(false), c -> c.val(5).pop());
		var bytes = cd.getClassFile();
		assertThat(lines(bytes, "run")).containsExactlyElementsOf(IntStream.rangeClosed(1, 17).boxed().toList());
		var cls = new ClassLoader(getClass().getClassLoader()){
			Class<?> load(){ return defineClass(null, bytes, 0, bytes.length); }
		}.load();
		cls.getMethod("run").invoke(null);
	}
	
	@Test
	public void runtimeFailureReportsInstructionNumber() throws Exception{
		var cls = TestUtils.generateAndLoadInstanceSimple("test.FailingInstructionLine", cd -> {
			cd.function("fail").staticAcc().returns(int.class).body()
			  .nullVal(String.class).call("length");
		});
		try{
			cls.getMethod("fail").invoke(null);
			throw new AssertionError("Expected a null dereference");
		}catch(InvocationTargetException e){
			assertThat(e.getCause()).isInstanceOf(NullPointerException.class);
			assertThat(e.getCause().getStackTrace()[0].getLineNumber()).isEqualTo(2);
		}
	}
}
