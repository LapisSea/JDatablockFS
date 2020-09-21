package com.lapissea.cfs.io.struct;

import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.util.NotNull;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.TextUtil;
import com.lapissea.util.function.FunctionOI;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Function;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.*;

public class ValueRelations{
	
	public record ValueInfo(
		@NotNull StructImpl.Annotated<Field, ?> value,
		Map<Class<? extends Annotation>, StructImpl.Annotated<Method, ?>> functions
	){
	}
	
	private static final boolean VALIDATE_VALUE_INDEX="true".equalsIgnoreCase(System.getProperty("com.lapissea.cfs.struct.index_validate", "true"));
	
	public final List<Map.Entry<String, ValueInfo>> data;
	
	public ValueRelations(StructImpl.RelationCollection raw){
		
		Function<Class<? extends Annotation>, Map<String, StructImpl.Annotated<Method, ? extends Annotation>>> get=
			typ->raw.get(typ)
			        .methods()
			        .stream()
			        .collect(toMap(e->{
				        var ann=e.annotation();
				
				        String target;
				        try{
					        target=(String)ann.getClass().getMethod("target").invoke(ann);
				        }catch(ReflectiveOperationException e1){
					        throw new ShouldNeverHappenError(e1);
				        }
				        if(!target.isEmpty()) return target;
				
				        var name   =e.val().getName();
				        var annName=ann.annotationType().getSimpleName().toLowerCase();
				
				        if(name.startsWith(annName)){
					        var cName=name.substring(annName.length());
					        if(!cName.isEmpty()&&Character.isUpperCase(cName.charAt(0))){
						        return TextUtil.firstToLowerCase(cName);
					        }
				        }
				
				        return name;
			        }, e->e));
		
		FunctionOI<Annotation> getIndex=a->{
			try{
				return (int)a.getClass().getMethod("index").invoke(a);
			}catch(ReflectiveOperationException e1){
				throw new ShouldNeverHappenError(e1);
			}
		};
		
		var values=
			IOStruct.VALUE_TYPES.stream()
			                    .map(type->raw.get(type).fields())
			                    .flatMap(Collection::stream)
			                    .sorted(Comparator.comparingInt(ann->getIndex.apply(ann.annotation())))
			                    .collect(toMap(e->e.val().getName(), e->e, (x, y)->y, LinkedHashMap::new));
		
		if(VALIDATE_VALUE_INDEX){
			
			var groups=values.values().stream().collect(groupingBy(ann->getIndex.apply(ann.annotation())));

//			var negs=groups.entrySet()
//			               .stream()
//			               .filter(e->e.getKey()<0)
//			               .flatMap(e->e.getValue().stream())
//			               .map(e->e.val().getName())
//			               .collect(joining("\n"));
//			if(!negs.isEmpty()){
//				throw new MalformedStructLayout("Invalid negative indices assigned to values:\n"+negs);
//			}
			
			var dups=groups.entrySet()
			               .stream()
			               .filter(e->e.getValue().size()>1)
			               .map(e->TextUtil.toString(e.getKey()+":", e.getValue().stream().map(an->an.val().getDeclaringClass().getSimpleName()+"."+an.val().getName())))
			               .collect(joining("\n"));
			if(!dups.isEmpty()){
				throw new MalformedStructLayout("Values must not have duplicated indices:\n"+dups);
			}
		}
		
		var enumFuns=IOStruct.TAG_TYPES.stream().map(get).collect(toList());
		
		data=values.entrySet().stream()
		           .map(entry->new AbstractMap.SimpleEntry<>(entry.getKey(), new ValueInfo(
			           entry.getValue(),
			           IntStream.range(0, IOStruct.TAG_TYPES.size())
			                    .mapToObj(i->new AbstractMap.SimpleEntry<>(IOStruct.TAG_TYPES.get(i), enumFuns.get(i).get(entry.getKey())))
			                    .filter(e->e.getValue()!=null)
			                    .collect(toMap(Map.Entry::getKey, Map.Entry::getValue))
		           )))
		           .collect(toUnmodifiableList());
		
		
	}
}
