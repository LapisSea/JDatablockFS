package com.lapissea.cfs.type.compilation;

import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.type.GetAnnotation;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.SupportedPrimitive;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.RecursiveTask;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
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
	
	private record FieldInfo(String name, Type type, List<Annotation> annotations, Optional<FieldStub> getter, Optional<FieldStub> setter){
		Stream<FieldStub> stubs(){return Stream.concat(getter.stream(), setter.stream());}
	}
	
	private static final class ImplNode<T extends IOInstance<T>>{
		enum State{
			NEW, COMPILING, DONE
		}
		
		private final Lock lock=new ReentrantLock();
		
		private State    state=State.NEW;
		private Class<T> impl;
	}
	
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
	
	private static final ConcurrentHashMap<Class<?>, ImplNode<?>> CACHE=new ConcurrentHashMap<>();
	
	public static <T extends IOInstance<T>> Class<T> compile(Class<T> interf){
		if(!interf.isInterface()||!UtilL.instanceOf(interf, IOInstance.Def.class)){
			throw new IllegalArgumentException(interf+"");
		}
		
		@SuppressWarnings("unchecked")
		var node=(ImplNode<T>)CACHE.computeIfAbsent(interf, i->new ImplNode<>());
		
		node.lock.lock();
		try{
			switch(node.state){
				case null -> throw new ShouldNeverHappenError();
				case NEW -> {}
				case COMPILING -> throw new MalformedStructLayout("Type requires itself to compile");
				case DONE -> {return node.impl;}
			}
			
			node.state=ImplNode.State.COMPILING;
			
			List<FieldStub> getters=new ArrayList<>();
			List<FieldStub> setters=new ArrayList<>();
			collectMethods(interf, getters, setters);
			
			checkStyles(getters, setters);
			
			var fieldInfo=mergeStubs(getters, setters);
			
			checkTypes(fieldInfo);
			checkAnnotations(fieldInfo);
			checkModel(fieldInfo);
			
			var impl=generateImpl(interf, fieldInfo);
			
			node.impl=impl;
			node.state=ImplNode.State.DONE;
			
			return impl;
		}catch(Throwable e){
			node.state=ImplNode.State.NEW;
			throw e;
		}finally{
			node.lock.unlock();
		}
	}
	
	private static <T extends IOInstance<T>> Class<T> generateImpl(Class<T> interf, List<FieldInfo> fieldInfo){
		throw new NotImplementedException();
	}
	
	private static <T extends IOInstance<T>> void collectMethods(Class<T> interf, List<FieldStub> getters, List<FieldStub> setters){
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
	}
	
	
	private static List<FieldInfo> mergeStubs(List<FieldStub> getters, List<FieldStub> setters){
		return Stream.concat(getters.stream(), setters.stream())
		             .map(FieldStub::varName)
		             .distinct()
		             .map(name->{
			             var getter=getters.stream().filter(s->s.varName.equals(name)).findAny();
			             var setter=setters.stream().filter(s->s.varName.equals(name)).findAny();
			
			             var type=getter.or(()->setter).map(FieldStub::type).orElseThrow();
			
			             var anns=Stream.concat(getter.stream(), setter.stream())
			                            .map(f->f.method.getAnnotations())
			                            .flatMap(Arrays::stream)
			                            .filter(a->FieldCompiler.ANNOTATION_TYPES.contains(a.annotationType()))
			                            .toList();
			
			             return new FieldInfo(name, type, anns, getter, setter);
		             })
		             .toList();
	}
	
	private static void checkStyles(List<FieldStub> getters, List<FieldStub> setters){
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
	}
	private static void checkAnnotations(List<FieldInfo> fields){
		var problems=fields.stream()
		                   .map(gs->{
			                        var dup=gs.stubs()
			                                  .flatMap(g->Arrays.stream(g.method.getAnnotations()))
			                                  .filter(a->FieldCompiler.ANNOTATION_TYPES.contains(a.annotationType()))
			                                  .collect(Collectors.groupingBy(Annotation::annotationType))
			                                  .values().stream()
			                                  .filter(l->l.size()>1)
			                                  .map(l->"\t\t"+l.get(0).annotationType().getName())
			                                  .collect(Collectors.joining("\n"));
			                        if(dup.isEmpty()) return "";
			                        return "\t"+gs.name+":\n"+dup;
		                        }
		                   ).filter(s->!s.isEmpty())
		                   .collect(Collectors.joining("\n"));
		
		if(!problems.isEmpty()){
			throw new MalformedStructLayout("Duplicate annotations:\n"+problems);
		}
	}
	private static void checkTypes(List<FieldInfo> fields){
		var problems=fields.stream()
		                   .filter(gs->gs.stubs().collect(Collectors.groupingBy(s->s.type)).size()>1)
		                   .map(gs->"\t"+gs.name+":\n"+
		                            "\t\t"+gs.stubs().map(g->(g.style!=Style.RAW?g.method.getName()+":\t":"")+g.type+"")
		                                     .collect(Collectors.joining("\n\t\t"))
		                   )
		                   .collect(Collectors.joining("\n"));
		
		if(!problems.isEmpty()){
			throw new MalformedStructLayout("Mismatched types:\n"+problems);
		}
	}
	private static void checkModel(List<FieldInfo> fieldInfo){
		var reg=FieldCompiler.registry();
		for(var field : fieldInfo){
			reg.requireCanCreate(field.type, GetAnnotation.from(field.annotations));
		}
	}
}
