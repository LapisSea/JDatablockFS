package com.lapissea.cfs.type.compilation;

import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.type.GetAnnotation;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.SupportedPrimitive;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.lapissea.cfs.type.SupportedPrimitive.BOOLEAN;

public class DefInstanceCompiler{
	
	private enum Style{
		NAMED("FieldType getName() / void setName(FieldType newValue)"),
		RAW("FieldType name() / void name(FieldType newValue)");
		
		final String humanPattern;
		Style(String humanPattern){this.humanPattern=humanPattern;}
	}
	
	private record FieldStub(Method method, String varName, Type type, Style style){}
	
	private static Optional<String> namePrefix(Method m, String prefix){
		var name=m.getName();
		if(name.length()<=prefix.length()) return Optional.empty();
		if(!name.startsWith(prefix)) return Optional.empty();
		if(Character.isLowerCase(name.charAt(prefix.length()))) return Optional.empty();
		return Optional.of(TextUtil.firstToLowerCase(name.substring(prefix.length())));
	}
	
	private static final List<Function<Method, Optional<FieldStub>>> GETTER_PATTERNS=List.of(
		m->{// FieldType getName()
			if(m.getParameterCount()!=0) return Optional.empty();
			var type=m.getGenericReturnType();
			if(type==void.class) return Optional.empty();
			
			return namePrefix(m, "get").map(name->new FieldStub(m, name, type, Style.NAMED));
		},
		m->{// (b/B)oolean isName()
			if(m.getParameterCount()!=0) return Optional.empty();
			var type=m.getGenericReturnType();
			if(SupportedPrimitive.get(type).orElse(null)!=BOOLEAN) return Optional.empty();
			
			return namePrefix(m, "is").map(name->new FieldStub(m, name, type, Style.NAMED));
		},
		m->{// FieldType name()
			if(m.getParameterCount()!=0) return Optional.empty();
			var name=m.getName();
			var type=m.getGenericReturnType();
			return Optional.of(new FieldStub(m, name, type, Style.RAW));
		}
	);
	private static final List<Function<Method, Optional<FieldStub>>> SETTER_PATTERNS=List.of(
		m->{// void setName(FieldType newValue)
			if(m.getParameterCount()!=1) return Optional.empty();
			if(m.getReturnType()!=void.class) return Optional.empty();
			
			return namePrefix(m, "set").map(name->new FieldStub(m, name, m.getGenericParameterTypes()[0], Style.NAMED));
		},
		m->{// void name(FieldType newValue)
			if(m.getParameterCount()!=1) return Optional.empty();
			if(m.getReturnType()!=void.class) return Optional.empty();
			var name=m.getName();
			var type=m.getGenericParameterTypes()[0];
			return Optional.of(new FieldStub(m, name, type, Style.RAW));
		}
	);
	
	private static final List<Class<?>> IGNORE_TYPES=
		Stream.concat(
			Stream.<Class<?>>iterate(
				IOInstance.Def.class,
				Objects::nonNull,
				cl->Stream.of(cl.getInterfaces())
				          .findAny()
				          .orElse(null)),
			Stream.of(Object.class)
		).toList();
	
	public static <T extends IOInstance<T>> Class<T> compile(Class<T> interf){
		if(!interf.isInterface()||!UtilL.instanceOf(interf, IOInstance.Def.class)){
			throw new IllegalArgumentException(interf+"");
		}
		
		LogUtil.println("compiling", interf);
		
		List<FieldStub> getters=new ArrayList<>();
		List<FieldStub> setters=new ArrayList<>();
		
		for(Method method : interf.getMethods()){
			if(IGNORE_TYPES.contains(method.getDeclaringClass())) continue;
			
			var getter=GETTER_PATTERNS.stream().map(f->f.apply(method)).filter(Optional::isPresent).map(Optional::get).findFirst();
			var setter=SETTER_PATTERNS.stream().map(f->f.apply(method)).filter(Optional::isPresent).map(Optional::get).findFirst();
			
			getter.ifPresent(getters::add);
			setter.ifPresent(setters::add);
			
			if(getter.or(()->setter).isEmpty()){
				throw new MalformedStructLayout(method+" is not a setter or a getter!");
			}
		}
		
		var styles=Stream.concat(getters.stream(), setters.stream()).collect(Collectors.groupingBy(FieldStub::style));
		
		if(styles.size()>1){
			var style=styles.entrySet().stream().reduce((a, b)->a.getValue().size()>b.getValue().size()?a:b).map(Map.Entry::getKey).orElseThrow();
			throw new MalformedStructLayout(
				"Inconsistent getter/setter styles!\n"+
				"Style patterns:\n"+styles.keySet().stream().map(s->"\t"+s+":\t"+s.humanPattern).collect(Collectors.joining("\n"))+"\n"+
				"Most common style is:\n"+style+"\n"+
				"Bad styles:\n"+
				styles.entrySet()
				      .stream()
				      .filter(e->e.getKey()!=style)
				      .map(Map.Entry::getValue)
				      .flatMap(Collection::stream)
				      .map(s->"\t"+s.method.getName()+":\t"+s.style)
				      .collect(Collectors.joining("\n"))
			);
		}
		
		var fields=Stream.concat(getters.stream(), setters.stream()).collect(Collectors.groupingBy(FieldStub::varName));
		
		var problems=fields.values()
		                   .stream()
		                   .filter(gs->gs.stream().collect(Collectors.groupingBy(s->s.type)).size()>1)
		                   .map(gs->"\t"+gs.get(0).varName+":\n"+
		                            "\t\t"+gs.stream().map(g->(g.style!=Style.RAW?g.method.getName()+":\t":"")+g.type+"")
		                                     .collect(Collectors.joining("\n\t\t"))
		                   )
		                   .collect(Collectors.joining("\n"));
		
		if(!problems.isEmpty()){
			throw new MalformedStructLayout("Mismatched types:\n"+problems);
		}
		problems=fields.values()
		               .stream()
		               .map(gs->{
			                    var dup=gs.stream()
			                                 .flatMap(g->Arrays.stream(g.method.getAnnotations()))
			                                 .collect(Collectors.groupingBy(Annotation::annotationType))
			                                 .values().stream()
			                                 .filter(l->l.size()>1)
			                                 .map(l->"\t\t"+l.get(0).annotationType().getName())
			                                 .collect(Collectors.joining("\n"));
			                    if(dup.isEmpty()) return "";
			                    return "\t"+gs.get(0).varName+":\n"+dup;
		                    }
		               ).filter(s->!s.isEmpty())
		               .collect(Collectors.joining("\n"));
		
		if(!problems.isEmpty()){
			throw new MalformedStructLayout("Duplicate annotations:\n"+problems);
		}
		
		var reg=FieldCompiler.create().registry();
		for(List<FieldStub> value : fields.values()){
			var type=value.get(0).type;
			var anns=value.stream().map(f->f.method.getAnnotations()).flatMap(Arrays::stream).toList();
			reg.requireCanCreate(type, GetAnnotation.from(anns));
		}
		
		throw new NotImplementedException();
	}
	
	
}
