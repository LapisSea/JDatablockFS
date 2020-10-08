package com.lapissea.cfs.io.struct.engine;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.io.struct.*;
import com.lapissea.util.Nullable;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.AbstractMap.SimpleEntry;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static java.util.stream.Collectors.*;

public class StructReflectionImpl implements StructImpl{
	
	public abstract static class NodeMaker<Base>{
		
		
		public record FunDef(Throwable t, Type returnType, Arg... args){
			
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

//				var retCheck=f.getGenericReturnType();
//				if(!retCheck.equals(returnType())){
//					t.printStackTrace();
//					throw new MalformedStructLayout(f+" should return "+TextUtil.toString(returnType())+" but returns "+retCheck);
//				}
				var args1=f.getGenericParameterTypes();
				
				if(args1.length!=args.length) throw new MalformedStructLayout(f+"\nshould have arguments\n"+Arrays.stream(args).map(TextUtil::toString).collect(joining("\n")));

//				for(int i=0;i<args1.length;i++){
//					if(!args1[i].equals(args[i].type)) throw new MalformedStructLayout(f+"\nshould have arguments\n"+Arrays.stream(args).map(TextUtil::toString).collect(joining("\n")));
//				}
				
				return Utils.makeLambda(f, fInter);
			}
		}
		
		protected abstract VariableNode<Base> makeNode(IOStruct clazz, String name, ValueRelations.ValueInfo info);
		
	}
	
	private static final Map<Class<? extends Annotation>, NodeMaker<?>> NODE_MAKERS=Map.ofEntries(
		new SimpleEntry<>(IOStruct.EnumValue.class, new EnumNodeMaker<>()),
		new SimpleEntry<>(IOStruct.Value.class, new GenericNodeMaker<>()),
		new SimpleEntry<>(IOStruct.PrimitiveValue.class, new PrimitiveNodeMaker<>()),
		new SimpleEntry<>(IOStruct.PointerValue.class, new PointerNodeMaker<>())
	                                                                                             );
	
	private VariableNode<?> infoToNode(IOStruct clazz, String name, ValueRelations.ValueInfo info){
		
		Annotated<Field, ?> val=info.value();
		
		return NODE_MAKERS.entrySet().stream()
		                  .filter(e->UtilL.instanceOf(val.annotation(), e.getKey()))
		                  .map(e->e.getValue().makeNode(clazz, name, info))
		                  .findAny().orElseThrow(()->new RuntimeException("Unable to generate node for "+name));
	}
	
	@Override
	public <T extends IOInstance> List<VariableNode<Object>> generateVariables(IOStruct clazz, RelationCollection data){
		var values=new ValueRelations(data);

//		LogUtil.println(TextUtil.toNamedPrettyJson(values.data));
		
		//noinspection unchecked
		return values.data.stream().map(entry->(VariableNode<Object>)infoToNode(clazz, entry.getKey(), entry.getValue())).collect(toList());
	}
}
