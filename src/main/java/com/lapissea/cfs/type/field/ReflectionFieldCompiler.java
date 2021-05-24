package com.lapissea.cfs.type.field;

import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.type.IOField;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.annotations.IOFieldDependency;
import com.lapissea.cfs.type.field.annotations.IOFieldMark;
import com.lapissea.cfs.type.field.reflection.IOFieldEnum;
import com.lapissea.cfs.type.field.reflection.IOFieldPrimitive;
import com.lapissea.util.UtilL;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import static com.lapissea.util.ZeroArrays.*;

public class ReflectionFieldCompiler extends FieldCompiler{
	@Override
	public <T extends IOInstance<T>> List<IOField<T, ?>> compile(Struct<T> struct){
		var cl=struct.getType();
		
		var rawFields=deepFieldsByAnnotation(cl, IOFieldMark.class);
		
		record Check<T extends IOInstance<T>>(Predicate<Class<?>> test, Function<Field, IOField<T, ?>> converter){}
		List<Check<T>> matches=List.of(
			new Check<>(IOFieldPrimitive::isPrimitive, IOFieldPrimitive::make),
			new Check<>(c->UtilL.instanceOf(c, Enum.class), IOFieldEnum::new)
		);
		
		record Pair(Field raw, int index){}
		
		List<Pair>          fields=new ArrayList<>();
		List<IOField<T, ?>> parsed=new ArrayList<>();
		
		for(Field field : rawFields){
			field.setAccessible(true);
			var fun=matches.stream().filter(e->e.test.test(field.getType())).findAny().map(Check::converter);
			if(fun.isEmpty()) throw new MalformedStructLayout("unable to resolve "+field.getName()+" as an IO field in "+field.getDeclaringClass().getSimpleName());
			fields.add(new Pair(field, fields.size()));
			parsed.add(fun.get().apply(field));
		}
		
		
		for(int i=0;i<fields.size();i++){
			Pair pair=fields.get(i);
			
			var depsAnn=pair.raw.getAnnotation(IOFieldDependency.class);
			var names  =depsAnn==null?ZERO_STRING:depsAnn.value();
			
			List<IOField<T, ?>> deps=new ArrayList<>(names.length);
			for(String name : names){
				var opt=fields
					        .stream()
					        .filter(f->f.raw.getName().equals(name))
					        .mapToInt(p->p.index)
					        .findAny();
				
				if(opt.isEmpty()) throw new MalformedStructLayout("Could not find dependency "+name+" on field "+pair.raw);
				deps.add(parsed.get(opt.getAsInt()));
			}
			
			parsed.get(i).initCommon(List.copyOf(deps), i);
		}
		
		return List.copyOf(parsed);
	}
}
