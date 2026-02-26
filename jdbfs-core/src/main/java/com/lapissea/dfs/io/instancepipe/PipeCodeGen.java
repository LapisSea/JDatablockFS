package com.lapissea.dfs.io.instancepipe;

import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.compilation.JorthLogger;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.SpecializedGenerator;
import com.lapissea.dfs.type.field.VirtualAccessor;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.iterableplus.Iters;
import com.lapissea.jorth.CodeStream;
import com.lapissea.jorth.Jorth;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.util.function.UnsafeConsumer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public final class PipeCodeGen{
	
	public interface PipeWriter<T extends IOInstance<T>>{
		void writePipeClass(
			CodeStream writer, Set<SpecializedGenerator.AccessMap.ConstantRequest> constants, Class<T> type
		) throws MalformedJorth, SpecializedGenerator.AccessMap.ConstantNeeded;
	}
	
	sealed interface ConstructionStrategy{
		record Setters() implements ConstructionStrategy{ }
		
		record Constructor(FieldSet<?> fields) implements ConstructionStrategy{ }
	}
	static void writeConstants(CodeStream writer, Set<SpecializedGenerator.AccessMap.ConstantRequest> constants, SpecializedGenerator.AccessMap accessMap) throws MalformedJorth{
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
					accessMap.addEnumArray(type, "#ThisClass", name);
				}
				case SpecializedGenerator.AccessMap.ConstantRequest.FieldAcc(var accessor) -> {
					var name = "acc_" + i + "_" + accessor.getName().replaceAll("[^A-Za-z]", "");
					accessors.add(new Acc(accessor, name));
					writer.write("private static final field {} {}<#ObjType>", name, VirtualAccessor.class);
					accessMap.addAccessorField(accessor, "#ThisClass", name);
				}
				case SpecializedGenerator.AccessMap.ConstantRequest.FieldRef(var ioField) -> {
					var name = "fieldRef_" + i + "_" + ioField.getName().replaceAll("[^A-Za-z]", "");
					fieldRefs.add(new FRef(ioField, name));
					writer.write("private static final field {} {}<#ObjType>", name, IOField.class);
					accessMap.addFieldRefField(ioField, "#ThisClass", name);
				}
				case SpecializedGenerator.AccessMap.ConstantRequest.DebugField(Class<?> type, String name, String ignore) -> {
					writer.write("public static final field {} {}", name, type);
				}
			}
		}
		
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
		}
		
		
		for(EArr enumArr : enumArrs){
			writer.write(
				"""
					static call {} values
					set #ThisClass {}
					""",
				enumArr.type, enumArr.name
			);
		}
		
		for(FRef fieldRef : fieldRefs){
		
		}
		
		for(var debugField : Iters.from(constants).instancesOf(SpecializedGenerator.AccessMap.ConstantRequest.DebugField.class)){
			writer.write(debugField.initCode());
			writer.write("set #ThisClass {}", debugField.name());
		}
		
		writer.wEnd();
	}
	static MethodHandle makeImpl(MethodHandles.Lookup lookup, String fnName, UnsafeConsumer<CodeStream, Throwable> generateFn) throws Throwable{
		var c     = lookup.lookupClass();
		var cname = c.getName();
		
		cname = (c.isHidden()? cname.substring(0, cname.lastIndexOf('/')) : cname) + "&_" + fnName;
		
		var log   = JorthLogger.make();
		var jorth = new Jorth(lookup.lookupClass().getClassLoader(), log);
		jorth.addImportAs(cname, "ThisClass");
		try{
			try(var writer = jorth.writer()){
				writer.write("class #ThisClass start");
				generateFn.accept(writer);
				writer.wEnd();
			}
			
			var bb        = jorth.getClassFile(cname);
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
	static <T extends IOInstance<T>> List<SpecializedGenerator> getSpecializedGenerators(Class<T> objType, Collection<IOField<T, ?>> fields){
		var struct = Struct.of(objType, Struct.STATE_FIELD_MAKE);
		if(fields == null){
			fields = StandardStructPipe.standardCompile(struct, struct.getFields(), false).fields();
		}
		
		List<SpecializedGenerator> generators = new ArrayList<>(fields.size());
		
		for(IOField<?, ?> field : fields){
			if(field instanceof SpecializedGenerator sg){
				generators.add(sg);
				continue;
			}
			throw new UnsupportedOperationException(
				Log.fmt("""
					        Not all fields support code generation:
					          Type:  {}#red
					          Field: {}#red - {}#red
					        """, objType.getTypeName(), field, field.getClass().getTypeName()));
		}
		return generators;
	}
	static <T extends IOInstance<T>> List<SpecializedGenerator> tryGetSpecializedGenerators(Class<T> type, Collection<IOField<T, ?>> fields){
		try{
			return getSpecializedGenerators(type, fields);
		}catch(UnsupportedOperationException t){
			if(ConfigDefs.CLASSGEN_SPECIALIZATION_FALLBACK.resolveVal()){
				Log.warn("Failed to generate specialization for {}#red because\n  {}", type, t);
				return null;
			}
			throw t;
		}
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
		CodeStream writer, List<SpecializedGenerator> generators, SpecializedGenerator.AccessMap accessMap, ConstructionStrategy strategy
	) throws MalformedJorth, SpecializedGenerator.AccessMap.ConstantNeeded{
		switch(strategy){
			case ConstructionStrategy.Setters ignore -> {
				accessMap.setup(false, false);
				writer.write("new #ObjType");
			}
			case ConstructionStrategy.Constructor ignore -> {
				accessMap.setup(false, true);
			}
		}
		
		for(SpecializedGenerator generator : generators){
			accessMap.markTemporary();
			generator.injectReadField(writer, accessMap);
			accessMap.dropTemporary(writer);
		}
		
		switch(strategy){
			case ConstructionStrategy.Setters ignore -> { }
			case ConstructionStrategy.Constructor(var fields) -> {
				writer.write("new #ObjType start");
				
				for(IOField<?, ?> field : fields){
					accessMap.get(field, writer);
				}
				
				writer.wEnd();
			}
		}
	}
	static void overwrite_readNew(CodeStream writer, List<SpecializedGenerator> generators, SpecializedGenerator.AccessMap accessMap, Struct<?> type) throws MalformedJorth, SpecializedGenerator.AccessMap.ConstantNeeded{
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
		if(generators != null){
			makeAndReadObj(writer, generators, accessMap, getStrategy(type));
		}else{
			writer.write(
				"""
					call-virtual
						bootstrap-fn #StandardStructPipe bootstrapReadNew
							arg #Class #ObjType
						calling-fn readNew start
							arg #DataProvider
							arg #ContentReader
							arg #GenericContext
							returns #ObjType
						end
					start
						get #arg provider
						get #arg src
						get #arg genericContext
					end
					"""
			);
		}
		writer.write(
			"""
					return
				end
				"""
		);
	}
	static void overwrite_doRead(CodeStream writer, List<SpecializedGenerator> generators, SpecializedGenerator.AccessMap accessMap) throws MalformedJorth, SpecializedGenerator.AccessMap.ConstantNeeded{
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
		if(generators != null){
			accessMap.setup(true, false);
			
			writer.write(
				"""
					get #arg instance
					cast #ObjType
					"""
			);
			
			for(SpecializedGenerator generator : generators){
				accessMap.markTemporary();
				generator.injectReadField(writer, accessMap);
				accessMap.dropTemporary(writer);
			}
			
		}else{
			writer.write(
				"""
					call-virtual
						bootstrap-fn #StandardStructPipe bootstrapDoRead
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
					"""
			);
		}
		writer.write(
			"""
					return
				end
				"""
		);
	}
}
