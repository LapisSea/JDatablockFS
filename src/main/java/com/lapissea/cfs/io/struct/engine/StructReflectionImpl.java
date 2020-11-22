package com.lapissea.cfs.io.struct.engine;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.StructImpl;
import com.lapissea.cfs.io.struct.ValueRelations;
import com.lapissea.cfs.io.struct.VariableNode;
import com.lapissea.util.Nullable;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.AbstractMap.SimpleEntry;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static java.util.stream.Collectors.*;

public class StructReflectionImpl implements StructImpl{
	
	public abstract static class NodeMaker{
		
		public static record FunDef(Throwable t, Type returnType, Arg... args){
			
			public FunDef(Type returnType, Arg... args){
				this(new Throwable(), returnType, args);
			}
			
			public record Arg(Type type, String name){
				@Override
				public String toString(){
					return type().getTypeName()+" "+name();
				}
			}
			
			
			@SuppressWarnings("unchecked")
			@Nullable
			public <FInter, T extends FInter> T getOverride(ValueRelations.ValueInfo info, Class<FInter> fInter){
				return getOverride(info, Objects.requireNonNull((Class<? extends Annotation>)fInter.getEnclosingClass()), fInter);
			}
			
			@Nullable
			public <FInter, T extends FInter> T getOverride(ValueRelations.ValueInfo info, Class<? extends Annotation> annType, Class<FInter> fInter){
				Annotated<Method, ?> ay=info.functions().get(annType);
				if(ay==null) return null;
				var f=ay.val();
				
				var args1=f.getGenericParameterTypes();
				
				if(args1.length!=args.length) throw new MalformedStructLayout(f+"\nshould have arguments\n"+Arrays.stream(args).map(TextUtil::toString).collect(joining("\n")));
				return Utils.makeLambda(f, fInter);
			}
		}
		
		protected abstract VariableNode<?> makeNode(IOStruct clazz, String name, ValueRelations.ValueInfo info);
		
	}
	
	private static final Map<Class<? extends Annotation>, NodeMaker> NODE_MAKERS=Map.ofEntries(
		new SimpleEntry<>(IOStruct.EnumValue.class, new EnumNodeMaker<>()),
		new SimpleEntry<>(IOStruct.Value.class, new GenericNodeMaker<>()),
		new SimpleEntry<>(IOStruct.PrimitiveValue.class, new PrimitiveNodeMaker()),
		new SimpleEntry<>(IOStruct.PointerValue.class, new PointerNodeMaker<>()),
		new SimpleEntry<>(IOStruct.PrimitiveArrayValue.class, new PrimitiveArrayNodeMaker()),
		new SimpleEntry<>(IOStruct.ArrayValue.class, new ArrayNodeMaker<>())
	                                                                                          );
	
	@SuppressWarnings("unchecked")
	@Override
	public List<VariableNode<Object>> generateVariables(IOStruct clazz, RelationCollection data){
		var values=new ValueRelations(data);
		
		return values.data.stream().map(entry->{
			String                   name=entry.getKey();
			ValueRelations.ValueInfo info=entry.getValue();
			
			Annotation val=info.value().annotation();
			
			return NODE_MAKERS.entrySet().stream()
			                  .filter(e->UtilL.instanceOf(val, e.getKey()))
			                  .map(Map.Entry::getValue)
			                  .map(nm->{
				                  try{
					                  return (VariableNode<Object>)nm.makeNode(clazz, name, info);
				                  }catch(Throwable e){
					                  throw new MalformedStructLayout("Failed to create node: "+name, e);
				                  }
			                  })
			                  .findAny()
			                  .orElseThrow(()->new RuntimeException("Unable to generate node for "+name));
		}).collect(toList());
	}
}
