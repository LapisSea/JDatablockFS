package com.lapissea.dfs.io.instancepipe;

import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.exceptions.UnsupportedCodeGenType;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.SpecializedGenerator;
import com.lapissea.iterableplus.Iters;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.Visibility;
import com.lapissea.jorth.redo.ClassDefinition;
import com.lapissea.jorth.redo.CodeArg;
import com.lapissea.jorth.redo.CodeBlock;
import com.lapissea.util.function.UnsafeConsumer;

import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class PipeCodeGen{
	
	public interface PipeWriter<T extends IOInstance<T>>{
		void writePipeClass(ClassDefinition cw, Class<T> type) throws MalformedJorth, UnsupportedCodeGenType;
	}
	
	sealed interface ConstructionStrategy{
		record Setters() implements ConstructionStrategy{ }
		
		record Constructor(FieldSet<?> fields) implements ConstructionStrategy{ }
	}
	static MethodHandle makeImpl(MethodHandles.Lookup lookup, String fnName, UnsafeConsumer<ClassDefinition, Throwable> generateFn) throws Throwable{
		var c     = lookup.lookupClass();
		var cname = c.getName();
		
		cname = (c.isHidden()? cname.substring(0, cname.lastIndexOf('/')) : cname) + "&_" + fnName;
		
		var cw = new ClassDefinition(lookup.lookupClass().getClassLoader());
		cw.typeDef("ThisClass", ClassName.dotted(cname));
		cw.name(ClassName.dotted(cname));
		generateFn.accept(cw);
		
		var bb = cw.getClassFile();
		
		var implClass = lookup.defineHiddenClass(bb, true, MethodHandles.Lookup.ClassOption.NESTMATE);
		var method = Iters.from(implClass.lookupClass().getMethods())
		                  .filter(e -> e.getName().equals(fnName))
		                  .getFirst();
		
		return implClass.unreflect(method);
	}
	static <T extends IOInstance<T>> List<SpecializedGenerator> getSpecializedGenerators(Class<T> objType, Collection<IOField<T, ?>> fields) throws UnsupportedCodeGenType{
		List<SpecializedGenerator> generators = new ArrayList<>(fields.size());
		
		for(IOField<?, ?> field : fields){
			if(field instanceof SpecializedGenerator sg){
				generators.add(sg);
				continue;
			}
			throw new UnsupportedCodeGenType(
				Log.fmt("""
					        Not all fields support code generation:
					          Type:  {}#red
					          Field: {}#red - {}#red
					        """, objType.getTypeName(), field, field.getClass().getTypeName()));
		}
		return generators;
	}
	
	static ConstructionStrategy getStrategy(Struct<?> type){
		var cls = type.getType();
		if(IOInstance.Def.isDefinition(cls)){
			return new ConstructionStrategy.Constructor(type.getRealFields());
		}
		
		if(IOFieldTools.tryGetOrImplyOrder(type).isPresent()) try{
			cls.getConstructor(type.getRealFields().mapped(IOField::getType).toArray(Class[]::new));
			
			return new ConstructionStrategy.Constructor(type.getRealFields());
			
		}catch(ReflectiveOperationException ignore){ }
		
		return new ConstructionStrategy.Setters();
	}
	static void makeAndReadObj(
		ClassDefinition cw, CodeBlock body, List<SpecializedGenerator> generators, SpecializedGenerator.AccessMap accessMap, ConstructionStrategy strategy
	) throws MalformedJorth, UnsupportedCodeGenType{
		switch(strategy){
			case ConstructionStrategy.Setters ignore -> {
				accessMap.setup(cw, false, false);
				body.newObj(body.getTypeDef("ObjType"));
			}
			case ConstructionStrategy.Constructor ignore -> {
				accessMap.setup(cw, false, true);
			}
		}
		
		for(SpecializedGenerator generator : generators){
			accessMap.markTemporary();
			try{
				generator.injectReadField(body, accessMap);
			}catch(UnsupportedCodeGenType e){
				throw new UnsupportedCodeGenType("Failed to generate code for: " + generator, e);
			}
			accessMap.dropTemporary(body);
		}
		
		switch(strategy){
			case ConstructionStrategy.Setters ignore -> { }
			case ConstructionStrategy.Constructor(var fields) -> {
				body.newObj(body.getTypeDef("ObjType"), args -> {
					for(IOField<?, ?> field : fields){
						accessMap.get(field, args);
					}
				});
			}
		}
	}
	static void overwrite_readNew(ClassDefinition cw, List<SpecializedGenerator> generators, SpecializedGenerator.AccessMap accessMap, Struct<?> type)
		throws MalformedJorth, UnsupportedCodeGenType{
		var body = cw.function("readNew").visibility(Visibility.PROTECTED)
		             .arg(DataProvider.class, "provider")
		             .arg(ContentReader.class, "src")
		             .arg(GenericContext.class, "genericContext")
		             .returns(IOInstance.class)
		             .annotation(Override.class)
		             .body();
		
		if(generators != null){
			makeAndReadObj(cw, body, generators, accessMap, getStrategy(type));
		}else{
			CodeArg getArgs = c -> c.get("provider")
			                        .get("src")
			                        .get("genericContext");
			body.callVirtual(
				    fn -> fn.caller(cw.getTypeDef("GeneratorPipeClass"), "bootstrapReadNew")
				            .arg(Class.class, cw.getTypeDef("ObjType")),
				    cf -> cf.name("readNew")
				            .arg(DataProvider.class)
				            .arg(ContentReader.class)
				            .arg(GenericContext.class)
				            .returns(IOInstance.class),
				    getArgs)
			    .dup()
			    .nullVal(IOInstance.class)
			    .ifEquality(code -> code.callSuper(getArgs).returnOp());
		}
		body.returnOp();
	}
	static void overwrite_doRead(ClassDefinition cw, List<SpecializedGenerator> generators, SpecializedGenerator.AccessMap accessMap) throws MalformedJorth, UnsupportedCodeGenType{
		
		ClassName   objType        = cw.getTypeDef("ObjType");
		GenericType objVarPoolType = GenericType.of(VarPool.class).withArgs(objType);
		
		var body = cw.function("doRead").visibility(Visibility.PROTECTED)
		             .arg(objVarPoolType, "ioPool")
		             .arg(DataProvider.class, "provider")
		             .arg(ContentReader.class, "src")
		             .arg(IOInstance.class, "instance")
		             .arg(GenericContext.class, "genericContext")
		             .returns(IOInstance.class)
		             .annotation(Override.class)
		             .body();
		if(generators != null){
			accessMap.setup(cw, true, false);
			
			body.get("instance")
			    .cast(objType);
			injectReadFields(body, generators, accessMap);
			
		}else{
			CodeArg getArgs = c -> c.get("ioPool")
			                        .get("provider")
			                        .get("src")
			                        .get("instance").cast(objType)
			                        .get("genericContext");
			body.callVirtual(
				    fn -> fn.caller(cw.getTypeDef("GeneratorPipeClass"), "bootstrapDoRead")
				            .arg(Class.class, objType),
				    cf -> cf.name("doRead")
				            .arg(objVarPoolType)
				            .arg(DataProvider.class)
				            .arg(ContentReader.class)
				            .arg(objType)
				            .arg(GenericContext.class)
				            .returns(objType),
				    getArgs)
			    .dup()
			    .nullVal(IOInstance.class)
			    .ifEquality(block -> block.callSuper(getArgs).returnOp());
		}
		body.returnOp();
	}
	
	private static <T extends IOInstance<T>> ConstantCallSite failedDoReadNew(MethodHandles.Lookup lookup, String name, Class<T> objType){
		try{
			return new ConstantCallSite(makeImpl(lookup, name, (cw) -> {
				cw.typeDef("ObjType", ClassName.of(objType));
				cw.function(name)
				  .arg(GenericType.of(VarPool.class).withArgs(objType), "ioPool")
				  .arg(DataProvider.class, "provider")
				  .arg(ContentReader.class, "src")
				  .arg(objType, "instance")
				  .arg(GenericContext.class, "genericContext")
				  .returns(objType)
				  .body()
				  .nullVal(objType);
			}));
		}catch(Throwable ex){
			throw new RuntimeException(ex);
		}
	}
	
	static <T extends IOInstance<T>> ConstantCallSite boostrapDoReadFromFields(
		MethodHandles.Lookup lookup, String name, Class<T> objType, List<IOField<T, ?>> fields
	){
		Log.debug("Generating specialized bootstrapDoRead for {}#green", objType.getTypeName());
		
		try{
			List<SpecializedGenerator> generators = getSpecializedGenerators(objType, fields);
			
			var target = makeImpl(lookup, "bootstrapDoRead", (cw) -> {
				cw.typeDef("ObjType", objType);
				
				var accessMap = new SpecializedGenerator.AccessMap();
				accessMap.setup(cw, true, false);
				
				Struct.of(objType, Struct.STATE_INIT_FIELDS);//Wait for fields to be initialized
				
				var bootstrapDoRead =
					cw.function("bootstrapDoRead").visibility(Visibility.PUBLIC).staticAcc()
					  .arg(GenericType.of(VarPool.class).withArgs(objType), "ioPool")
					  .arg(DataProvider.class, "provider")
					  .arg(ContentReader.class, "src")
					  .arg(objType, "instance")
					  .arg(GenericContext.class, "genericContext")
					  .returns(objType);
				
				CodeBlock body = bootstrapDoRead.body().get("instance");
				
				injectReadFields(body, generators, accessMap);
			});
			return new ConstantCallSite(target);
		}catch(UnsupportedCodeGenType e){
			if(ConfigDefs.OPTIMIZED_PIPE.resolve() != ConfigDefs.PipeOptimization.TRY_ALWAYS){
				throw new RuntimeException("Failed to make specialized generators for " + objType.getTypeName());
			}
			Log.info("Failed to make specialized generators for {}#red because\n  {}", objType.getTypeName(), e);
			return failedDoReadNew(lookup, name, objType);
		}catch(Throwable t){
			throw new RuntimeException("Failed to generate specialized implementation for " + objType.getTypeName(), t);
		}
	}
	
	private static void injectReadFields(CodeBlock body, List<SpecializedGenerator> generators, SpecializedGenerator.AccessMap accessMap) throws MalformedJorth, UnsupportedCodeGenType{
		for(SpecializedGenerator generator : generators){
			accessMap.markTemporary();
			try{
				generator.injectReadField(body, accessMap);
			}finally{
				accessMap.dropTemporary(body);
			}
		}
	}
	
	private static <T extends IOInstance<T>> ConstantCallSite failedReadNew(MethodHandles.Lookup lookup, String name, Class<T> objType){
		try{
			return new ConstantCallSite(makeImpl(lookup, name, body -> {
				body.typeDef("ObjType", objType);
				body.function(name).staticAcc()
				    .arg(DataProvider.class, "provider")
				    .arg(ContentReader.class, "src")
				    .arg(GenericContext.class, "genericContext")
				    .returns(IOInstance.class)
				    .body()
				    .nullVal(IOInstance.class);
			}));
		}catch(Throwable ex){
			ex.printStackTrace();
			throw new RuntimeException(ex);
		}
	}
	
	static <T extends IOInstance<T>> ConstantCallSite boostrapReadNewFromFields(
		MethodHandles.Lookup lookup, String name, Class<T> objType, List<IOField<T, ?>> fields
	){
		Log.debug("Generating specialized {}#yellow for {}#green", name, objType.getTypeName());
		
		try{
			List<SpecializedGenerator> generators = getSpecializedGenerators(objType, fields);
			
			var target = makeImpl(lookup, name, cw -> {
				cw.typeDef("ObjType", objType);
				var accessMap = new SpecializedGenerator.AccessMap();
				
				ConstructionStrategy strategy = getStrategy(Struct.of(objType, Struct.STATE_INIT_FIELDS));
				var body = cw.function(name).staticAcc()
				             .arg(DataProvider.class, "provider")
				             .arg(ContentReader.class, "src")
				             .arg(GenericContext.class, "genericContext")
				             .returns(IOInstance.class)
				             .body();
				
				makeAndReadObj(cw, body, generators, accessMap, strategy);
				
				body.returnOp();
			});
			return new ConstantCallSite(target);
		}catch(UnsupportedCodeGenType e){
			if(ConfigDefs.OPTIMIZED_PIPE.resolve() != ConfigDefs.PipeOptimization.TRY_ALWAYS){
				throw new RuntimeException("Failed to make specialized generators for " + objType.getTypeName());
			}
			Log.info("Failed to make specialized generators for {}#red because\n  {}", objType.getTypeName(), e);
			return failedReadNew(lookup, name, objType);
		}catch(Throwable t){
			throw new RuntimeException("Failed to generate specialized implementation for " + objType.getTypeName(), t);
		}
	}
	static void defaultClassDef(ClassDefinition cw) throws MalformedJorth{
		var superType = GenericType.of(cw.getTypeDef("GeneratorPipeClass")).withArgs(cw.getTypeDef("ObjType"));
		cw.name(cw.getTypeDef("ThisClass")).extendsType(superType)
		  .implement(StructPipe.SpecializedImplementation.class);
		
		cw.instanceInit()
		  .body()
		  .callSuper(c -> {
			  c.call(Struct.class, "of", c2 -> c2.val(cw.getTypeDef("ObjType")))
			   .val(StructPipe.STATE_DONE);
		  });
		
		cw.function("getGenericType").visibility(Visibility.PUBLIC)
		  .returns(Class.class)
		  .body().val(cw.getTypeDef("GeneratorPipeClass"));
	}
	
	public static void standardPipeImpl(
		Class<?> concreteType, ClassDefinition cw, Struct<?> type,
		List<SpecializedGenerator> generators
	) throws MalformedJorth, UnsupportedCodeGenType{
		var accessMap = new SpecializedGenerator.AccessMap();
		
		boolean noCtor = concreteType.isAnnotationPresent(Struct.NoDefaultConstructor.class);
		
		overwrite_doRead(cw, noCtor? generators : null, accessMap);
		
		if(!noCtor){
			overwrite_readNew(cw, generators, accessMap, type);
		}
	}
}
