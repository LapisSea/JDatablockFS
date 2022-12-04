package com.lapissea.jorth.v2.lang;

import com.lapissea.jorth.v2.Jorth;
import com.lapissea.jorth.v2.lang.type.*;
import com.lapissea.util.NanoTimer;
import com.lapissea.util.UtilL;
import org.objectweb.asm.ClassWriter;

import java.lang.invoke.MethodHandles;
import java.util.*;
import java.util.regex.Pattern;

import static org.objectweb.asm.Opcodes.V19;

public class Preload{
	
	private static void preload(MethodHandles.Lookup l, Class<?> cls, Set<Class<?>> added){
		if(cls.isArray()){
			preload(l, cls.componentType(), added);
			return;
		}
		if(cls.getPackageName().startsWith("java.")) return;
		try{
			l.accessClass(cls);
		}catch(Exception e){
			return;
		}
		if(!added.add(cls)) return;
		
		Thread.ofVirtual().start(() -> {
			
			try{
				l.ensureInitialized(cls);
			}catch(Exception ignored){ }
			
			if(cls.isInterface()){
				var permitted = cls.getPermittedSubclasses();
				if(permitted != null){
					for(Class<?> c : permitted){
						preload(l, c, added);
					}
				}
				for(Class<?> c : cls.getClasses()){
					preload(l, c, added);
				}
			}
		});
	}
	
	private static void preload(){
		Thread.ofVirtual().start(() -> {
			try{
				ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS|ClassWriter.COMPUTE_FRAMES);
				w.visit(V19, 0, "", "", "", null);
				w.visitField(0, "", "", null, null).visitEnd();
				var m = w.visitMethod(0, "", "()V", null, null);
				m.visitMaxs(0, 0);
				m.visitEnd();
				w.visitEnd();
				w.toByteArray();
			}catch(Throwable e){
				e.printStackTrace();
			}
		});
		Thread.ofVirtual().start(() -> {
			try{
				var cg = new ClassGen(TypeSource.of(Jorth.class.getClassLoader()), ClassName.of(Object.class), ClassType.CLASS, Visibility.PUBLIC, GenericType.OBJECT, List.of(), Set.of());
				cg.defineField(Visibility.PUBLIC, Set.of(), GenericType.OBJECT, "");
				var fun = new FunctionGen(cg, "", Visibility.PUBLIC, Set.of(), GenericType.OBJECT, new LinkedHashMap<>());
				fun.getOp("this", "");
				fun.end();
				cg.end();
			}catch(Throwable e){
				e.printStackTrace();
			}
		});
		
		Thread.ofVirtual().start(() -> {
			var           l     = MethodHandles.lookup();
			Set<Class<?>> added = Collections.synchronizedSet(new HashSet<>());
			
			for(var cls : List.of(
				Jorth.class,
				GenericType.class, Tokenizer.class,
				Keyword.class, ClassType.class, Visibility.class, Access.class,
				Token.class, TypeSource.class,
				ClassGen.class,
				ClassGen.FieldGen.class,
				FunctionGen.class,
				Pattern.class, UtilL.class, NanoTimer.Simple.class,
				Optional.class, TypeStack.class,
				
				org.objectweb.asm.Type.class,
				org.objectweb.asm.MethodTooLargeException.class,
				org.objectweb.asm.MethodVisitor.class,
				org.objectweb.asm.FieldVisitor.class,
				org.objectweb.asm.Label.class,
				org.objectweb.asm.ByteVector.class,
				org.objectweb.asm.ClassWriter.class,
				org.objectweb.asm.Opcodes.class,
				NanoTimer.class
			)){
				preload(l, cls, added);
			}
			
			for(Access access : EnumSet.noneOf(Access.class)){ }
			for(Object v : new LinkedHashMap<>().values()){ }
		});
	}
	
	static{
		preload();
	}
	
	public static void init(){ }
}
