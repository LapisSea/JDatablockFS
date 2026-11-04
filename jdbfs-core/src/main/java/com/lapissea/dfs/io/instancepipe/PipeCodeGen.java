package com.lapissea.dfs.io.instancepipe;

import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.config.GlobalConfig;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.exceptions.UnsupportedCodeGenType;
import com.lapissea.dfs.exceptions.MalformedStruct;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.type.field.SpecializedGenerator;
import com.lapissea.iterableplus.Iters;
import com.lapissea.jorth.ClassDefinition;
import com.lapissea.jorth.CodeArg;
import com.lapissea.jorth.CodeBlock;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.Visibility;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public final class PipeCodeGen{
	
	public interface PipeWriter<T extends IOInstance<T>>{
		void writePipeClass(ClassDefinition cw, Class<T> type, List<Object> constants) throws MalformedJorth, UnsupportedCodeGenType;
	}
	
	sealed interface ConstructionStrategy{
		record Setters() implements ConstructionStrategy{ }
		
		record Constructor(FieldSet<?> fields) implements ConstructionStrategy{ }
	}
	// A sealed root may be an ordinary interface; only its concrete pipe values are IOInstances.
	@SuppressWarnings({"rawtypes", "unchecked"})
	public static <T extends IOInstance<T>> StructPipe<T> resolveSealedPipe(
		DataProvider provider, Class rootType, int id, Map<Class<T>, StructPipe<T>> pipes
	) throws IOException{
		var type = provider.getTypeDb().fromID(rootType, id);
		if(type == null) throw new MalformedStruct("fmt", "Unknown sealed id {}#red for root type {}#yellow", id, rootType);
		return pipes.get(type);
	}

	// The generated class supplies its own lookup; class data keeps captured objects alive with that class.
	public static Object classConstant(MethodHandles.Lookup lookup, int index) throws IllegalAccessException{
		return MethodHandles.classDataAt(lookup, "_", Object.class, index);
	}

	public static void loadConstant(CodeBlock body, List<Object> constants, Object value, Class<?> type) throws MalformedJorth{
		int index = constants.size();
		constants.add(value);
		body.call(PipeCodeGen.class, "classConstant", args -> args.call(MethodHandles.class, "lookup").val(index)).cast(type);
	}

	static MethodHandle makeImpl(MethodHandles.Lookup lookup, String fnName, UnsafeConsumer<ClassDefinition, Throwable> generateFn) throws Throwable{
		return makeImpl(lookup, fnName, new ArrayList<>(), generateFn);
	}
	static MethodHandle makeImpl(MethodHandles.Lookup lookup, String fnName, List<Object> constants, UnsafeConsumer<ClassDefinition, Throwable> generateFn) throws Throwable{
		var c     = lookup.lookupClass();
		var cname = c.getName();
		
		cname = (c.isHidden()? cname.substring(0, cname.lastIndexOf('/')) : cname) + "&_" + fnName;
		
		var cw = ClassDefinition.hiddenNestmate(lookup.lookupClass()).finalAcc();
		cw.typeDef("ThisClass", ClassName.dotted(cname));
		cw.name(ClassName.dotted(cname));
		generateFn.accept(cw);
		
		var bb = cw.getClassFile();
		
		var implClass = lookup.defineHiddenClassWithClassData(bb, List.copyOf(constants), true, MethodHandles.Lookup.ClassOption.NESTMATE);
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
		if(IOInstance.Def.isDefinition(cls) || IOFieldTools.tryGetOrImplyOrder(type).isPresent()) try{
			type.getConcreteType().getConstructor(type.getRealFields().mapped(IOField::getType).toArray(Class[]::new));
			
			return new ConstructionStrategy.Constructor(type.getRealFields());
			
		}catch(ReflectiveOperationException ignore){ }
		
		return new ConstructionStrategy.Setters();
	}
	static void makeAndReadObj(
		ClassDefinition cw, CodeBlock body, List<SpecializedGenerator> generators, SpecializedGenerator.AccessMap accessMap, ConstructionStrategy strategy
	) throws MalformedJorth, UnsupportedCodeGenType{
		switch(strategy){
			case ConstructionStrategy.Setters ignore -> {
				accessMap.setup(false, false);
				body.newObj(body.getTypeDef("ObjType"));
				accessMap.captureObject(body);
			}
			case ConstructionStrategy.Constructor ignore -> {
				accessMap.setup(false, true);
			}
		}
		
		injectReadFields(body, generators, accessMap);

		switch(strategy){
			case ConstructionStrategy.Setters ignore -> accessMap.getObject(body);
			case ConstructionStrategy.Constructor(var fields) -> {
				body.newObj(body.getTypeDef("ObjType"), args -> {
					for(IOField<?, ?> field : fields){
						accessMap.get(field, args);
					}
				});
				accessMap.storeConstructedInstanceFields(body);
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
			accessMap.setup(true, false);
			
			body.get("instance")
			    .cast(objType);
			accessMap.captureObject(body);
			injectReadFields(body, generators, accessMap);
			accessMap.getObject(body);
			
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
				cw.function(name).staticAcc()
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
		if(ConfigDefs.CompLogLevel.SMALL.isEnabled()){
			Log.debug("Generating specialized bootstrapDoRead for {}#green", objType.getTypeName());
		}
		
		try{
			List<SpecializedGenerator> generators = getSpecializedGenerators(objType, fields);
			
			var constants = new ArrayList<Object>();
			var target = makeImpl(lookup, "bootstrapDoRead", constants, (cw) -> {
				cw.typeDef("ObjType", objType);
				
				var accessMap = new SpecializedGenerator.AccessMap(cw, constants);
				accessMap.setup(true, false);
				
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
				accessMap.captureObject(body);
				
				injectReadFields(body, generators, accessMap);
				accessMap.getObject(body);
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
		if(!body.stackView().isEmpty()) throw new MalformedJorth("Field reads require an empty operand stack");

		for(SpecializedGenerator generator : generators){
			accessMap.markTemporary();
			try{
				generator.injectReadField(body, accessMap);
				if(!body.stackView().isEmpty()){
					throw new MalformedJorth("Field read left values on the operand stack: " + generator + " " + body.stackView());
				}
			}catch(UnsupportedCodeGenType e){
				throw new UnsupportedCodeGenType("Failed to generate code for: " + generator, e);
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
		if(ConfigDefs.CompLogLevel.SMALL.isEnabled()){
			Log.debug("Generating specialized {}#yellow for {}#green", name, objType.getTypeName());
		}
		
		try{
			List<SpecializedGenerator> generators = getSpecializedGenerators(objType, fields);
			
			var constants = new ArrayList<Object>();
			var target = makeImpl(lookup, name, constants, cw -> {
				cw.typeDef("ObjType", objType);
				var accessMap = new SpecializedGenerator.AccessMap(cw, constants);
				
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
	public static <T extends IOInstance<T>> void writeKnownSize(StructPipe<T> pipe, VarPool<T> pool, DataProvider provider, ContentWriter dest, T instance, IOField<T, ?> field) throws IOException{
		pipe.writeFieldKnownSize(pool, provider, dest, instance, field);
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	public static Object getAccessorValue(Object accessor, VarPool<?> pool, IOInstance<?> instance){
		return ((FieldAccessor)accessor).get(pool, instance);
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	public static void setAccessorValue(Object accessor, VarPool<?> pool, IOInstance<?> instance, Object value){
		((FieldAccessor)accessor).set(pool, instance, value);
	}

	public static IOException reportWriteFailure(IOException failure, IOField<?, ?> field){
		failure.addSuppressed(new IOException("Failed to write " + field));
		return failure;
	}

	private static void injectWriteFields(CodeBlock body, List<SpecializedGenerator> generators, SpecializedGenerator.AccessMap accessMap, String pipeLocal) throws MalformedJorth, UnsupportedCodeGenType{
		accessMap.setupForWrite();
		body.get("instance").cast(body.getTypeDef("ObjType"));
		accessMap.captureObject(body);
		body.get(GlobalConfig.class, "DEBUG_VALIDATION").ifTrue(debug -> {
			for(var generator : generators){
				debug.call(PipeCodeGen.class, "writeKnownSize", args -> {
					args.get(pipeLocal).get("ioPool").get("provider").get("dest");
					accessMap.getObject(args);
					accessMap.getFieldRef((IOField<?, ?>)generator, args);
				});
			}
		}).elseRun(direct -> {
			for(var generator : generators){
				direct.tryRun(write -> {
					accessMap.markTemporary();
					try{
						generator.injectWriteField(write, accessMap);
						if(!write.stackView().isEmpty()) throw new MalformedJorth("Field write left values on the operand stack: " + generator);
					}catch(UnsupportedCodeGenType e){
						throw com.lapissea.util.UtilL.uncheckedThrow(e);
					}finally{ accessMap.dropTemporary(write); }
				}).tryCatch(IOException.class, "writeError", error -> {
					error.call(PipeCodeGen.class, "reportWriteFailure", args -> {
						args.get("writeError");
						accessMap.getFieldRef((IOField<?, ?>)generator, args);
					}).throwOp();
				});
			}
		});
	}

	private static void overwriteWriteFields(ClassDefinition cw, List<SpecializedGenerator> generators, SpecializedGenerator.AccessMap accessMap, int pipeIndex) throws MalformedJorth, UnsupportedCodeGenType{
		var objType = cw.getTypeDef("ObjType");
		var poolType = GenericType.of(VarPool.class).withArgs(objType);
		var body = cw.function("writeFields").visibility(Visibility.PROTECTED)
		             .arg(FieldSet.class, "fields").arg(poolType, "ioPool")
		             .arg(DataProvider.class, "provider").arg(ContentWriter.class, "dest")
		             .arg(IOInstance.class, "instance").annotation(Override.class).body();
		CodeArg superArgs = args -> args.get("fields").get("ioPool").get("provider").get("dest").get("instance");
		body.get("fields").get("this").call("getSpecificFields").equalityOp().ifTrue(full -> {
			if(generators != null){
				try{ injectWriteFields(full, generators, accessMap, "this"); }
				catch(UnsupportedCodeGenType e){ throw com.lapissea.util.UtilL.uncheckedThrow(e); }
			}else{
				full.callVirtual(fn -> fn.caller(PipeCodeGen.class, "bootstrapWriteFields").arg(Class.class, objType).arg(int.class, pipeIndex),
				                 call -> call.name("writeFields").arg(StructPipe.class).arg(poolType).arg(DataProvider.class).arg(ContentWriter.class).arg(objType).returns(boolean.class),
				                 args -> args.get("this").get("ioPool").get("provider").get("dest").get("instance").cast(objType))
				    .ifTrue(done -> done.returnOp()).elseRun(fallback -> fallback.callSuper(superArgs));
			}
		}).elseRun(partial -> partial.callSuper(superArgs));
	}

	public static <T extends IOInstance<T>> CallSite bootstrapWriteFields(MethodHandles.Lookup lookup, String name, MethodType ignore, Class<T> objType, int pipeIndex) throws Throwable{
		var source = (StructPipe<?>)classConstant(lookup, pipeIndex);
		source.waitForState(StructPipe.LOCAL_DATA);
		var constants = new ArrayList<Object>();
		List<SpecializedGenerator> generators;
		try{
			@SuppressWarnings({"unchecked", "rawtypes"})
			var fields = (Collection<IOField<T, ?>>)(Collection)source.getSpecificFields();
			generators = getSpecializedGenerators(objType, fields);
		}catch(UnsupportedCodeGenType e){
			if(ConfigDefs.OPTIMIZED_PIPE.resolve() != ConfigDefs.PipeOptimization.TRY_ALWAYS) throw e;
			generators = null;
		}
		var compiled = generators;
		return new ConstantCallSite(makeImpl(lookup, name, constants, cw -> {
			cw.typeDef("ObjType", objType);
			var access = new SpecializedGenerator.AccessMap(cw, constants);
			var body = cw.function(name).staticAcc().arg(StructPipe.class, "pipe")
			             .arg(GenericType.of(VarPool.class).withArgs(objType), "ioPool")
			             .arg(DataProvider.class, "provider").arg(ContentWriter.class, "dest").arg(objType, "instance")
			             .returns(boolean.class).body();
			if(compiled != null) injectWriteFields(body, compiled, access, "pipe");
			body.val(compiled != null).returnOp();
		}));
	}

	private static void overwriteSkipFields(ClassDefinition cw, int pipeIndex) throws MalformedJorth{
		var objType = cw.getTypeDef("ObjType");
		var poolType = GenericType.of(VarPool.class).withArgs(objType);
		var body = cw.function("skipFields").visibility(Visibility.PROTECTED)
		             .arg(poolType, "ioPool").arg(DataProvider.class, "provider").arg(ContentReader.class, "src")
		             .arg(IOInstance.class, "instance").arg(GenericContext.class, "genericContext").annotation(Override.class).body();
		body.callVirtual(fn -> fn.caller(PipeCodeGen.class, "bootstrapSkipFields").arg(Class.class, objType).arg(int.class, pipeIndex),
		                 call -> call.name("skipFields").arg(poolType).arg(DataProvider.class).arg(ContentReader.class).arg(objType).arg(GenericContext.class),
		                 args -> args.get("ioPool").get("provider").get("src").get("instance").cast(objType).get("genericContext"));
	}

	public static <T extends IOInstance<T>> CallSite bootstrapSkipFields(MethodHandles.Lookup lookup, String name, MethodType ignore, Class<T> objType, int pipeIndex) throws Throwable{
		var source = (StandardStructPipe<?>)classConstant(lookup, pipeIndex);
		source.waitForState(StructPipe.LOCAL_DATA);
		var steps = source.skipSteps();
		var constants = new ArrayList<Object>();
		return new ConstantCallSite(makeImpl(lookup, name, constants, cw -> {
			cw.typeDef("ObjType", objType);
			var access = new SpecializedGenerator.AccessMap(cw, constants);
			var body = cw.function(name).staticAcc()
			             .arg(GenericType.of(VarPool.class).withArgs(objType), "ioPool")
			             .arg(DataProvider.class, "provider").arg(ContentReader.class, "src")
			             .arg(objType, "instance").arg(GenericContext.class, "genericContext").body();
			access.setupForWrite();
			body.get("instance");
			access.captureObject(body);
			for(var step : steps){
				if(step.operation() == StandardStructPipe.SKIP_FIXED){
					body.get("src").call("skipExact", args -> args.val(step.bytes()));
				}else if(step.operation() == StandardStructPipe.SKIP && step.field() instanceof SpecializedGenerator generator){
					access.markTemporary();
					try{
						generator.injectSkipField(body, access);
						if(!body.stackView().isEmpty()) throw new MalformedJorth("Field skip left values on the operand stack: " + generator);
					}finally{ access.dropTemporary(body); }
				}else{
					access.getFieldRef(step.field(), body);
					body.call(step.operation() == StandardStructPipe.READ? "read" : "skip", args -> args.get("ioPool").get("provider").get("src").get("instance").get("genericContext"));
				}
			}
		}));
	}

	static void defaultClassDef(ClassDefinition cw) throws MalformedJorth{
		defaultClassDef(cw, c -> c.call(Struct.class, "of", c2 -> c2.val(cw.getTypeDef("ObjType"))).get("syncStage"));
	}
	static void defaultClassDef(ClassDefinition cw, CodeArg superArgs) throws MalformedJorth{
		var superType = GenericType.of(cw.getTypeDef("GeneratorPipeClass")).withArgs(cw.getTypeDef("ObjType"));
		cw.name(cw.getTypeDef("ThisClass")).extendsType(superType)
		  .implement(StructPipe.SpecializedImplementation.class);
		
		cw.instanceInit()
		  .arg(int.class, "syncStage")
		  .body()
		  .callSuper(superArgs);
		
		cw.function("getGenericType").visibility(Visibility.PUBLIC)
		  .returns(Class.class)
		  .body().val(cw.getTypeDef("GeneratorPipeClass"));
	}
	
	public static void standardPipeImpl(
		Class<?> concreteType, ClassDefinition cw, Struct<?> type,
		List<SpecializedGenerator> generators, List<Object> constants
	) throws MalformedJorth, UnsupportedCodeGenType{
		standardPipeImpl(concreteType, cw, type, generators, constants, false, null);
	}
	static void standardPipeImpl(Class<?> concreteType, ClassDefinition cw, Struct<?> type, List<SpecializedGenerator> generators, List<Object> constants, StructPipe<?> sourcePipe) throws MalformedJorth, UnsupportedCodeGenType{
		standardPipeImpl(concreteType, cw, type, generators, constants, false, sourcePipe);
	}
	static void standardPipeImpl(
		Class<?> concreteType, ClassDefinition cw, Struct<?> type,
		List<SpecializedGenerator> generators, List<Object> constants, boolean eagerDoRead, StructPipe<?> sourcePipe
	) throws MalformedJorth, UnsupportedCodeGenType{
		var accessMap = new SpecializedGenerator.AccessMap(cw, constants);
		
		if(sourcePipe != null){
			int pipeIndex = constants.size();
			constants.add(sourcePipe);
			overwriteWriteFields(cw, generators, accessMap, pipeIndex);
			if(sourcePipe instanceof StandardStructPipe<?>) overwriteSkipFields(cw, pipeIndex);
		}

		boolean noCtor = concreteType.isAnnotationPresent(Struct.NoDefaultConstructor.class);
		
		if(eagerDoRead){
			if(!type.needsBuilderObj()){
				overwrite_doRead(cw, generators, accessMap);
			}
			if(!noCtor || getStrategy(type) instanceof ConstructionStrategy.Constructor){
				overwrite_readNew(cw, generators, accessMap, type);
			}
			return;
		}
		overwrite_doRead(cw, noCtor? generators : null, accessMap);
		
		if(!noCtor){
			overwrite_readNew(cw, generators, accessMap, type);
		}
	}
}
