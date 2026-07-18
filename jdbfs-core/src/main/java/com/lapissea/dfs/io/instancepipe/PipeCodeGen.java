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
import com.lapissea.dfs.type.compilation.JorthLogger;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.SpecializedGenerator;
import com.lapissea.dfs.type.field.VirtualAccessor;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.iterableplus.Iters;
import com.lapissea.jorth.BytecodeUtils;
import com.lapissea.jorth.CodeStream;
import com.lapissea.jorth.Jorth;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.Visibility;
import com.lapissea.jorth.redo.ClassDefinition;
import com.lapissea.jorth.redo.CodeArg;
import com.lapissea.jorth.redo.CodeBlock;
import com.lapissea.util.function.UnsafeBiConsumer;

import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class PipeCodeGen{
	
	public interface PipeWriter<T extends IOInstance<T>>{
		void writePipeClass(
			CodeStream writer, Set<SpecializedGenerator.AccessMap.ConstantRequest> constants, ClassDefinition cw, Class<T> type
		) throws MalformedJorth, SpecializedGenerator.AccessMap.ConstantNeeded, UnsupportedCodeGenType;
	}
	
	sealed interface ConstructionStrategy{
		record Setters() implements ConstructionStrategy{ }
		
		record Constructor(FieldSet<?> fields) implements ConstructionStrategy{ }
	}
	static void writeConstants(CodeStream writer, ClassDefinition cw, Set<SpecializedGenerator.AccessMap.ConstantRequest> constants, SpecializedGenerator.AccessMap accessMap) throws MalformedJorth{
		record Acc(FieldAccessor<?> accessor, String name){ }
		record FRef(IOField<?, ?> field, String name){ }
		record EArr(Class<?> type, String name){ }
		List<Acc>  accessors = new ArrayList<>();
		List<FRef> fieldRefs = new ArrayList<>();
		List<EArr> enumArrs  = new ArrayList<>();
		
		int i = -1;
		for(SpecializedGenerator.AccessMap.ConstantRequest constant : constants){
			i++;
			switch(constant){
				case SpecializedGenerator.AccessMap.ConstantRequest.EnumArr(var type) -> {
					var name = "eArr_" + i + "_" + type.getSimpleName().replaceAll("[^A-Za-z]", "");
					enumArrs.add(new EArr(type, name));
					writer.write("private static final field {} {}", name, type.arrayType());
					accessMap.addEnumArray(type, cw.getTypeDef("ThisClass"), name);
				}
				case SpecializedGenerator.AccessMap.ConstantRequest.FieldAcc(var accessor) -> {
					var name = "acc_" + i + "_" + accessor.getName().replaceAll("[^A-Za-z]", "");
					accessors.add(new Acc(accessor, name));
					writer.write("private static final field {} {}<#ObjType>", name, VirtualAccessor.class);
					accessMap.addAccessorField(accessor, cw.getTypeDef("ThisClass"), name);
				}
				case SpecializedGenerator.AccessMap.ConstantRequest.FieldRef(var ioField) -> {
					var name = "fieldRef_" + i + "_" + ioField.getName().replaceAll("[^A-Za-z]", "");
					fieldRefs.add(new FRef(ioField, name));
					writer.write("private static final field {} {}<#ObjType>", name, IOField.class);
					accessMap.addFieldRefField(ioField, cw.getTypeDef("ThisClass"), name);
				}
				case SpecializedGenerator.AccessMap.ConstantRequest.DebugField(Class<?> type, String name, String ignore) -> {
					writer.write("public static final field {} {}", name, type);
					cw.field(name, type);
				}
			}
		}
		
		cw.staticInit();
		writer.write("public static function <clinit> start");
		
		if(!accessors.isEmpty()){
			writer.write(
				"""
						static call #Struct of start
							class #ObjType
							{}
						end
						call getFields
					""", Struct.STATE_FIELD_MAKE);
			for(var it = accessors.iterator(); it.hasNext(); ){
				var acc = it.next();
				if(it.hasNext()){
					writer.write("dup");
				}
				writer.write("call requireByName start '{}' end", acc.accessor.getName());
				writer.write("call getAccessor cast {}", VirtualAccessor.class);
				writer.write("set #ThisClass {}", acc.name);
			}
			
			var cinit = cw.staticInit().body()
			              .call(Struct.class, "of", e -> e.val(cw.getTypeDef("ObjType")).val(Struct.STATE_FIELD_MAKE))
			              .call("getFields");
			
			for(var it = accessors.iterator(); it.hasNext(); ){
				var acc = it.next();
				if(it.hasNext()){
					cinit.dup();
				}
				var type = GenericType.of(VirtualAccessor.class).withArgs(cw.getTypeDef("ObjType"));
				cinit.call("requireByName", e -> e.val(acc.accessor.getName()))
				     .call("getAccessor").cast(VirtualAccessor.class)
				     .set(cw.field(acc.name, type).visibility(Visibility.PRIVATE).staticFinal());
				cinit.call("cast", e -> e.val(cw.getTypeDef("ObjType")));
			}
			
		}
		
		if(!fieldRefs.isEmpty()){
			writer.write(
				"""
						static call #Struct of start
							class #ObjType
							{}
						end
						call getFields
					""", Struct.STATE_FIELD_MAKE);
			for(var it = fieldRefs.iterator(); it.hasNext(); ){
				var acc = it.next();
				if(it.hasNext()){
					writer.write("dup");
				}
				writer.write("call requireByName start '{}' end", acc.field.getName());
				writer.write("set #ThisClass {}", acc.name);
			}
			
			var cinit = cw.staticInit().body()
			              .call(Struct.class, "of", e -> e.val(cw.getTypeDef("ObjType")).val(Struct.STATE_FIELD_MAKE))
			              .call("getFields");
			
			for(var it = fieldRefs.iterator(); it.hasNext(); ){
				var acc = it.next();
				if(it.hasNext()){
					cinit.dup();
				}
				cinit.call("requireByName", e -> e.val(acc.field.getName()))
				     .set(cw.field(acc.name, IOField.class).visibility(Visibility.PRIVATE).staticFinal());
			}
		}
		
		
		for(EArr enumArr : enumArrs){
			writer.write(
				"""
					static call {} values
					set #ThisClass {}
					""",
				enumArr.type, enumArr.name
			);
			
			cw.field(enumArr.name, enumArr.type.arrayType())
			  .visibility(Visibility.PRIVATE).staticFinal(e -> e.call(enumArr.type, "values"));
		}
		
		for(var debugField : Iters.from(constants).instancesOf(SpecializedGenerator.AccessMap.ConstantRequest.DebugField.class)){
			writer.write(debugField.initCode());
			writer.write("set #ThisClass {}", debugField.name());
			
			cw.field(debugField.name(), debugField.type())
			  .visibility(Visibility.PUBLIC).staticFinal(e -> {
				  if(debugField.type() == boolean.class){ // TODO: Ugly hack. Make initCode propert type
					  e.val(Boolean.parseBoolean(debugField.initCode()));
				  }else{
					  e.val(debugField.initCode());
				  }
			  });
		}
		
		writer.wEnd();
	}
	static MethodHandle makeImpl(MethodHandles.Lookup lookup, String fnName, UnsafeBiConsumer<CodeStream, ClassDefinition, Throwable> generateFn) throws Throwable{
		var c     = lookup.lookupClass();
		var cname = c.getName();
		
		cname = (c.isHidden()? cname.substring(0, cname.lastIndexOf('/')) : cname) + "&_" + fnName;
		
		var log   = JorthLogger.make();
		var jorth = new Jorth(lookup.lookupClass().getClassLoader(), log);
		var cw    = new ClassDefinition(lookup.lookupClass().getClassLoader());
		jorth.addImportAs(cname, "ThisClass");
		cw.typeDef("ThisClass", ClassName.dotted(cname));
		try{
			try(var writer = jorth.writer()){
				writer.addImports(
					VarPool.class, DataProvider.class, ContentReader.class,
					GenericContext.class, Struct.class, IOInstance.class
				);
				writer.write("class #ThisClass start");
				cw.name(ClassName.dotted(cname));
				generateFn.accept(writer, cw);
				writer.wEnd();
			}
			
			var bb    = jorth.getClassFile(cname);
			var bbNew = cw.getClassFile();
			BytecodeUtils.compareClasses(bbNew, bb);
			
			var implClass = lookup.defineHiddenClass(bb, true, MethodHandles.Lookup.ClassOption.NESTMATE);
			var method = Iters.from(implClass.lookupClass().getMethods())
			                  .filter(e -> e.getName().equals(fnName))
			                  .getFirst();
			
			return implClass.unreflect(method);
		}catch(SpecializedGenerator.AccessMap.ConstantNeeded e){
			log = null;
			throw e;
		}finally{
			if(log != null){
				Log.log("Generated jorth for bootstrap implementation:\n" + log.output());
			}
		}
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
		CodeStream writer, CodeBlock body, List<SpecializedGenerator> generators, SpecializedGenerator.AccessMap accessMap, ConstructionStrategy strategy
	) throws MalformedJorth, SpecializedGenerator.AccessMap.ConstantNeeded, UnsupportedCodeGenType{
		switch(strategy){
			case ConstructionStrategy.Setters ignore -> {
				accessMap.setup(false, false);
				writer.write("new #ObjType");
				body.newObj(body.getTypeDef("ObjType"));
			}
			case ConstructionStrategy.Constructor ignore -> {
				accessMap.setup(false, true);
			}
		}
		
		for(SpecializedGenerator generator : generators){
			accessMap.markTemporary();
			try{
				generator.injectReadField(writer, body, accessMap);
			}catch(UnsupportedCodeGenType e){
				throw new UnsupportedCodeGenType("Failed to generate code for: " + generator, e);
			}
			accessMap.dropTemporary(writer, body);
		}
		
		switch(strategy){
			case ConstructionStrategy.Setters ignore -> { }
			case ConstructionStrategy.Constructor(var fields) -> {
				writer.write("new #ObjType start");
				
				for(IOField<?, ?> field : fields){
					accessMap.get(field, writer);
				}
				
				writer.wEnd();
				
				body.newObj(body.getTypeDef("ObjType"), args -> {
					for(IOField<?, ?> field : fields){
						accessMap.get(field, args);
					}
				});
			}
		}
	}
	static void overwrite_readNew(CodeStream writer, ClassDefinition cw, List<SpecializedGenerator> generators, SpecializedGenerator.AccessMap accessMap, Struct<?> type)
		throws MalformedJorth, SpecializedGenerator.AccessMap.ConstantNeeded, UnsupportedCodeGenType{
		writer.write(
			"""
				@ #Override
				protected function readNew
					arg provider #DataProvider
					arg src #ContentReader
					arg genericContext #GenericContext
					returns #IOInstance
				start
				"""
		);
		var body = cw.function("readNew").visibility(Visibility.PROTECTED)
		             .arg(DataProvider.class, "provider")
		             .arg(ContentReader.class, "src")
		             .arg(GenericContext.class, "genericContext")
		             .returns(IOInstance.class)
		             .annotation(Override.class)
		             .body();
		
		if(generators != null){
			makeAndReadObj(writer, body, generators, accessMap, getStrategy(type));
		}else{
			writer.write(
				"""
					call-virtual
						bootstrap-fn #GeneratorPipeClass bootstrapReadNew
							arg #Class #ObjType
						calling-fn readNew start
							arg #DataProvider
							arg #ContentReader
							arg #GenericContext
							returns #IOInstance
						end
					start
						get #arg provider
						get #arg src
						get #arg genericContext
					end
					dup null ==
					if start
						super start
							get #arg provider
							get #arg src
							get #arg genericContext
						end
						return
					end
					"""
			);
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
		writer.write(
			"""
					return
				end
				"""
		);
		body.returnOp();
	}
	static void overwrite_doRead(CodeStream writer, ClassDefinition cw, List<SpecializedGenerator> generators, SpecializedGenerator.AccessMap accessMap) throws MalformedJorth, SpecializedGenerator.AccessMap.ConstantNeeded, UnsupportedCodeGenType{
		writer.write(
			"""
				@ #Override
				protected function doRead
					arg ioPool #VarPool<#ObjType>
					arg provider #DataProvider
					arg src #ContentReader
					arg instance #IOInstance
					arg genericContext #GenericContext
					returns #IOInstance
				start
				"""
		);
		
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
			
			writer.write(
				"""
					get #arg instance
					cast #ObjType
					"""
			);
			body.get("instance")
			    .cast(objType);
			injectReadFields(writer, body, generators, accessMap);
			
		}else{
			writer.write(
				"""
					call-virtual
						bootstrap-fn #GeneratorPipeClass bootstrapDoRead
							arg #Class #ObjType
						calling-fn doRead start
							arg #VarPool<#ObjType>
							arg #DataProvider
							arg #ContentReader
							arg #ObjType
							arg #GenericContext
							returns #ObjType
						end
					start
						get #arg ioPool
						get #arg provider
						get #arg src
						get #arg instance cast #ObjType
						get #arg genericContext
					end
					dup null ==
					if start
						super start
							get #arg ioPool
							get #arg provider
							get #arg src
							get #arg instance cast #ObjType
							get #arg genericContext
						end
						return
					end
					"""
			);
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
		writer.write(
			"""
					return
				end
				"""
		);
		body.returnOp();
	}
	
	private static <T extends IOInstance<T>> ConstantCallSite failedDoReadNew(MethodHandles.Lookup lookup, String name, Class<T> objType){
		try{
			return new ConstantCallSite(makeImpl(lookup, name, (writer, cw) -> {
				writer.addImportAs(objType, "ObjType");
				cw.typeDef("ObjType", ClassName.of(objType));
				writer.write(
					"""
						public static function {}
							arg ioPool #VarPool<#ObjType>
							arg provider #DataProvider
							arg src #ContentReader
							arg instance #ObjType
							arg genericContext #GenericContext
							returns #ObjType
						start
							null start #ObjType end
							return
						end
						""", name
				);
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
		Set<SpecializedGenerator.AccessMap.ConstantRequest> constants = new LinkedHashSet<>();
		
		try{
			List<SpecializedGenerator> generators = getSpecializedGenerators(objType, fields);
			
			while(true){
				try{
					var target = makeImpl(lookup, "bootstrapDoRead", (writer, cw) -> {
						writer.addImportAs(objType, "ObjType");
						cw.typeDef("ObjType", objType);
						
						var accessMap = new SpecializedGenerator.AccessMap();
						accessMap.setup(true, false);
						
						writeConstants(writer, cw, constants, accessMap);
						
						Struct.of(objType, Struct.STATE_INIT_FIELDS);//Wait for fields to be initialized
						
						writer.write(
							"""
								public static function bootstrapDoRead
									arg ioPool #VarPool<#ObjType>
									arg provider #DataProvider
									arg src #ContentReader
									arg instance #ObjType
									arg genericContext #GenericContext
									returns #ObjType
								start
									get #arg instance
								""",
							name
						);
						var bootstrapDoRead =
							cw.function("bootstrapDoRead").visibility(Visibility.PUBLIC).staticAcc()
							  .arg(GenericType.of(VarPool.class).withArgs(objType), "ioPool")
							  .arg(DataProvider.class, "provider")
							  .arg(ContentReader.class, "src")
							  .arg(objType, "instance")
							  .arg(GenericContext.class, "genericContext")
							  .returns(objType);
						
						CodeBlock body = bootstrapDoRead.body().get("instance");
						
						injectReadFields(writer, body, generators, accessMap);
						
						writer.write(
							"""
									return
								end
								"""
						);
						
					});
					return new ConstantCallSite(target);
				}catch(SpecializedGenerator.AccessMap.ConstantNeeded e){
					constants.addAll(e.constants);
				}
			}
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
	
	private static void injectReadFields(CodeStream writer, CodeBlock body, List<SpecializedGenerator> generators, SpecializedGenerator.AccessMap accessMap) throws MalformedJorth, SpecializedGenerator.AccessMap.ConstantNeeded, UnsupportedCodeGenType{
		Set<SpecializedGenerator.AccessMap.ConstantRequest> constantsReq = null;
		
		for(SpecializedGenerator generator : generators){
			accessMap.markTemporary();
			try{
				generator.injectReadField(writer, body, accessMap);
			}catch(SpecializedGenerator.AccessMap.ConstantNeeded e){
				if(constantsReq == null) constantsReq = new LinkedHashSet<>();
				constantsReq.addAll(e.constants);
			}catch(Throwable e){
				//codegen probably from last field
				if(constantsReq != null){
					throw new SpecializedGenerator.AccessMap.ConstantNeeded(constantsReq);
				}
				throw e;
			}finally{
				accessMap.dropTemporary(writer, body);
			}
		}
		if(constantsReq != null){
			throw new SpecializedGenerator.AccessMap.ConstantNeeded(constantsReq);
		}
	}
	
	private static <T extends IOInstance<T>> ConstantCallSite failedReadNew(MethodHandles.Lookup lookup, String name, Class<T> objType){
		try{
			return new ConstantCallSite(makeImpl(lookup, name, (writer, body) -> {
				writer.addImportAs(objType, "ObjType");
				body.typeDef("ObjType", objType);
				writer.write(
					"""
						public static function {}
							arg provider #DataProvider
							arg src #ContentReader
							arg genericContext #GenericContext
							returns #IOInstance
						start
							null start #IOInstance end
							return
						end
						""", name
				);
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
		
		Set<SpecializedGenerator.AccessMap.ConstantRequest> constants = new LinkedHashSet<>();
		try{
			List<SpecializedGenerator> generators = getSpecializedGenerators(objType, fields);
			
			while(true){
				try{
					var target = makeImpl(lookup, name, (writer, cw) -> {
						writer.addImportAs(objType, "ObjType");
						cw.typeDef("ObjType", objType);
						var accessMap = new SpecializedGenerator.AccessMap();
						
						writeConstants(writer, cw, constants, accessMap);
						
						ConstructionStrategy strategy = getStrategy(Struct.of(objType, Struct.STATE_INIT_FIELDS));
						writer.write(
							"""
								public static function {}
									arg provider #DataProvider
									arg src #ContentReader
									arg genericContext #GenericContext
									returns #IOInstance
								start
								""", name
						);
						var body = cw.function(name).staticAcc()
						             .arg(DataProvider.class, "provider")
						             .arg(ContentReader.class, "src")
						             .arg(GenericContext.class, "genericContext")
						             .returns(IOInstance.class)
						             .body();
						
						makeAndReadObj(writer, body, generators, accessMap, strategy);
						
						writer.write(
							"""
									return
								end
								"""
						);
						body.returnOp();
					});
					return new ConstantCallSite(target);
				}catch(SpecializedGenerator.AccessMap.ConstantNeeded e){
					constants.addAll(e.constants);
				}
			}
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
	static void defaultClassDef(CodeStream writer, ClassDefinition cw) throws MalformedJorth{
		writer.write(
			"""
				extends #GeneratorPipeClass<#ObjType>
				implements {}
				public class #ThisClass start
				""",
			StructPipe.SpecializedImplementation.class
		);
		writer.write(
			"""
				public function <init> start
					super start
						static call #Struct of start
							class #ObjType
						end
						{!}
					end
				end
				
				public function getGenericType
					returns #Class
				start
					class #GeneratorPipeClass
				end
				""",
			StructPipe.STATE_DONE
		);
		
		var superType = GenericType.of(cw.getTypeDef("GeneratorPipeClass")).withArgs(cw.getTypeDef("ObjType"));
		cw.name(cw.getTypeDef("ThisClass")).extendsType(superType)
		  .implement(StructPipe.SpecializedImplementation.class);
		
		cw.instanceInit()
		  .body()
		  .callSuper(c -> c.call(Struct.class, "of", c2 -> c2.val(cw.getTypeDef("ObjType")))
		                   .val(StructPipe.STATE_DONE));
		
		cw.function("getGenericType").visibility(Visibility.PUBLIC)
		  .returns(Class.class)
		  .body().val(cw.getTypeDef("GeneratorPipeClass"));
	}
	
	public static void standardPipeImpl(
		CodeStream writer, Set<SpecializedGenerator.AccessMap.ConstantRequest> constants,
		Class<?> concreteType, ClassDefinition cw, Struct<?> type,
		List<SpecializedGenerator> generators
	) throws MalformedJorth, SpecializedGenerator.AccessMap.ConstantNeeded, UnsupportedCodeGenType{
		var accessMap = new SpecializedGenerator.AccessMap();
		writeConstants(writer, cw, constants, accessMap);
		
		boolean noCtor = concreteType.isAnnotationPresent(Struct.NoDefaultConstructor.class);
		
		overwrite_doRead(writer, cw, noCtor? generators : null, accessMap);
		
		if(!noCtor){
			overwrite_readNew(writer, cw, generators, accessMap, type);
		}
	}
}
