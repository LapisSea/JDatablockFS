package com.lapissea.jorth.lang;

import com.lapissea.jorth.Jorth;
import com.lapissea.jorth.lang.type.Access;
import com.lapissea.jorth.lang.type.ClassGen;
import com.lapissea.jorth.lang.type.ClassType;
import com.lapissea.jorth.lang.type.FunctionGen;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.TypeSource;
import com.lapissea.jorth.lang.type.TypeStack;
import com.lapissea.jorth.lang.type.Visibility;
import com.lapissea.util.NanoTimer;
import com.lapissea.util.UtilL;
import org.objectweb.asm.ClassWriter;

import java.lang.invoke.MethodHandles;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
		
		Thread.startVirtualThread(() -> {
			
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
		Thread.startVirtualThread(() -> {
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
		Thread.startVirtualThread(() -> {
			try{
				var cg = new ClassGen(TypeSource.of(Jorth.class.getClassLoader()), ClassName.of(Object.class), ClassType.CLASS, Visibility.PUBLIC, GenericType.OBJECT, List.of(), Set.of(), List.of());
				cg.defineField(Visibility.PUBLIC, Set.of(), Set.of(), GenericType.OBJECT, "");
				var fun = new FunctionGen(cg, "", Visibility.PUBLIC, Set.of(), GenericType.OBJECT, List.of(), List.of());
				fun.getThisOp("");
				fun.end();
				cg.end();
			}catch(Throwable e){
				e.printStackTrace();
			}
		});
		
		Thread.startVirtualThread(() -> {
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
