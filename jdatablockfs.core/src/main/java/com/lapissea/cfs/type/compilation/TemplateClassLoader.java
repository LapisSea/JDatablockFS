package com.lapissea.cfs.type.compilation;

import com.lapissea.cfs.config.ConfigDefs;
import com.lapissea.cfs.logging.Log;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.IOTypeDB;
import com.lapissea.cfs.type.TypeDef;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.access.AnnotatedType;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.jorth.CodeStream;
import com.lapissea.jorth.Jorth;
import com.lapissea.jorth.MalformedJorth;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.TextUtil;
import com.lapissea.util.WeakValueHashMap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static com.lapissea.util.ConsoleColors.*;

public final class TemplateClassLoader extends ClassLoader{
	
	static{
		registerAsParallelCapable();
	}
	
	private record TypeNamed(String name, TypeDef def){ }
	
	private static final Map<TypeNamed, byte[]> CLASS_DATA_CACHE = Collections.synchronizedMap(new WeakValueHashMap<>());
	
	private static final boolean PRINT_GENERATING_INFO = ConfigDefs.CLASSGEN_PRINT_GENERATING_INFO.resolve();
	
	private final IOTypeDB db;
	
	public TemplateClassLoader(IOTypeDB db, ClassLoader parent){
		super(TemplateClassLoader.class.getSimpleName() + "{" + db + "}", parent);
		this.db = db;
	}
	
	@Override
	protected Class<?> findClass(String className) throws ClassNotFoundException{
		TypeDef def = getDef(className);
		if(def.isUnmanaged()){
			throw new UnsupportedOperationException(className + " is unmanaged! All unmanaged types must be present! Unmanaged types may contain mechanism not understood by the base IO engine.");
		}
		
		if(!def.isIoInstance() && !def.isEnum() && !def.isJustInterface()){
			throw new UnsupportedOperationException("Can not generate: " + className + ". It is not an " + IOInstance.class.getSimpleName() + " or Enum or just an interface");
		}
		
		var classData = CLASS_DATA_CACHE.get(new TypeNamed(className, def));
		if(classData == null){
			var typ = new TypeNamed(className, def.clone());
			
			var hash = hashCode();
			Log.trace("Generating template: {} - {}", className, (Supplier<String>)() -> {
				var cols = List.of(BLACK, RED, GREEN, YELLOW, BLUE, PURPLE, CYAN);
				return cols.get((int)(Integer.toUnsignedLong(hash)%cols.size())) + Integer.toHexString(hash) + " " + RESET;
			});
			
			try{
				classData = jorthGenerate(typ);
			}catch(Throwable e){
				if(ConfigDefs.CLASSGEN_EXIT_ON_FAIL.resolveVal()){
					e.printStackTrace();
					System.exit(-1);
				}
				throw e;
			}
			CLASS_DATA_CACHE.put(typ, classData);
		}
		
		return defineClass(className, ByteBuffer.wrap(classData), null);
	}
	
	private byte[] jorthGenerate(TypeNamed classType){
		if(PRINT_GENERATING_INFO) LogUtil.println("generating", "\n" + TextUtil.toTable(classType.name, classType.def.getFields()));
		
		var log   = JorthLogger.make();
		var jorth = new Jorth(this, log == null? null : log::log);
		
		try(var writer = jorth.writer()){
			if(classType.def.isIoInstance()){
				generateIOInstance(classType, writer);
			}else if(classType.def.isEnum()){
				generateEnum(classType, writer);
			}else if(classType.def.isJustInterface()){
				generateJustAnInterface(classType, writer);
			}
		}catch(MalformedJorth e){
			throw new RuntimeException("Failed to generate class " + classType.name, e);
		}
		if(log != null) Log.log(log.output());
		
		var bytecode = jorth.getClassFile(classType.name);
		ClassGenerationCommons.dumpClassName(classType.name, bytecode);
		
		return bytecode;
	}
	
	private void generateEnum(TypeNamed classType, CodeStream writer) throws MalformedJorth{
		writer.write(
			"""
				public enum {!} start
				""",
			classType.name
		);
		for(var constant : classType.def.getEnumConstants()){
			writer.write("enum {!}", constant.getName());
		}
		writer.write("end");
	}
	
	private void generateIOInstance(TypeNamed classType, CodeStream writer) throws MalformedJorth{
		writer.addImportAs(classType.name, "genClassName");
		
		writePermits(classType, writer);
		
		var parent = classType.def.getSealedParent();
		if(parent != null){
			ensureLoadedSealParent(parent.name());
			switch(parent.type()){
				case EXTEND -> {
					//Need to rework ioinstance generation to be a full class first
					throw new NotImplementedException("A sealed parent as a class is not implemented yet");
				}
				case JUST_INTERFACE -> writer.write("implements {!}", parent.name());
			}
		}
		writer.write("implements {}<#genClassName>", IOInstance.Def.class);
		
		writer.write("public interface #genClassName start");
		
		for(var field : classType.def.getFields()){
			if(IONullability.NullLogic.canHave(new AnnotatedType.Simple(
				field.isDynamic()? List.of(IOFieldTools.makeAnnotation(IOValue.Generic.class)) : List.of(),
				field.getType().getTypeClass(db)
			))){
				writer.write("@{} start value {!} end", IONullability.class, field.getNullability().toString());
			}
			if(field.isDynamic()){
				writer.write("@{}", IOValue.Generic.class);
			}
			if(field.isUnsigned()){
				writer.write("@{}", IOValue.Unsigned.class);
			}
			if(field.getReferenceType() != null){
				writer.write("@{} start dataPipeType {!} end", IOValue.Reference.class, field.getReferenceType().toString());
			}
			if(!field.getDependencies().isEmpty()){
				try(var ignored = writer.codePart()){
					writer.write(" @{} start value [", IODependency.class);
					for(String dependency : field.getDependencies()){
						writer.write("'{}'", dependency);
					}
					writer.write("] end");
				}
			}
			
			writer.write(
				"""
					function {!0}
						returns {1}
					end
					function {!0}
						arg {!0} {1}
					end
					""",
				field.getName(),
				field.getType().generic(db));
		}
		writer.write("end");
	}
	
	private void generateJustAnInterface(TypeNamed classType, CodeStream writer) throws MalformedJorth{
		writePermits(classType, writer);
		
		var parent = classType.def.getSealedParent();
		if(parent != null){
			ensureLoadedSealParent(parent.name());
			switch(parent.type()){
				case EXTEND -> {
					throw new IllegalStateException("Interface can not have an extends");
				}
				case JUST_INTERFACE -> writer.write("implements {!}", parent.name());
			}
		}
		writer.write("public interface {!} start end", classType.name);
	}
	
	private void ensureLoadedSealParent(String pName){
		try{
			getDef(pName);
		}catch(ClassNotFoundException e){
			throw new IllegalStateException("Sealed parent must be registered before a child", e);
		}
	}
	
	private static void writePermits(TypeNamed classType, CodeStream writer) throws MalformedJorth{
		if(classType.def.isSealed()){
			for(var subclass : classType.def.getPermittedSubclasses()){
				writer.write("permits {!}", subclass);
			}
		}
	}
	
	private TypeDef getDef(String name) throws ClassNotFoundException{
		try{
			return db.getDefinitionFromClassName(name).orElseThrow(() -> {
				return new ClassNotFoundException(name + " is not defined in database");
			});
		}catch(IOException e){
			throw new RuntimeException("Failed to fetch data from database", e);
		}
	}
}
