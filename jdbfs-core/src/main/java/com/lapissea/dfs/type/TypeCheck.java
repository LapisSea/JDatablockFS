package com.lapissea.dfs.type;

import com.lapissea.dfs.SealedUtil;
import com.lapissea.dfs.exceptions.InvalidGenericArgument;
import com.lapissea.dfs.utils.Iters;

import java.lang.reflect.Modifier;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public class TypeCheck{
	public interface ArgCheck{
		interface RawCheck{
			
			ArgCheck.RawCheck PRIMITIVE        = of(SupportedPrimitive::isAny, "is not primitive");
			ArgCheck.RawCheck INSTANCE         = of(
				type -> {
					if(SealedUtil.getSealedUniverse(type, false).filter(IOInstance::isInstance).isPresent()){
						return true;
					}
					
					if(!IOInstance.isInstance(type)){
						return false;
					}
					if(!Modifier.isAbstract(type.getModifiers())){
						return true;
					}
					return IOInstance.Def.isDefinition(type);
				},
				type -> {
					if(Modifier.isAbstract(type.getModifiers()) && !IOInstance.Def.isDefinition(type)){
						return type.getSimpleName() + " is an IOInstance but is not an instantiable class";
					}
					return type.getSimpleName() + " is not an IOInstance";
				});
			ArgCheck.RawCheck INSTANCE_MANAGED = INSTANCE.and(of(IOInstance::isManaged, "is not a managed IOInstance"));
			
			
			default ArgCheck.RawCheck and(ArgCheck.RawCheck other){
				var that = this;
				return new ArgCheck.RawCheck(){
					@Override
					public boolean check(Class<?> type){
						return that.check(type) && other.check(type);
					}
					@Override
					public String errMsg(Class<?> type){
						if(that.check(type)) return that.errMsg(type);
						return other.errMsg(type);
					}
				};
			}
			static ArgCheck.RawCheck of(Predicate<Class<?>> check, String errTypeAnd){
				return of(check, type -> type.getSimpleName() + " " + errTypeAnd);
			}
			static ArgCheck.RawCheck of(Predicate<Class<?>> check, Function<Class<?>, String> errorMessage){
				return new ArgCheck.RawCheck(){
					@Override
					public boolean check(Class<?> type){
						return check.test(type);
					}
					@Override
					public String errMsg(Class<?> type){
						return errorMessage.apply(type);
					}
				};
			}
			
			boolean check(Class<?> type);
			String errMsg(Class<?> type);
			
			default ArgCheck arg(){
				return (type, db) -> {
					var resolved = type.getTypeClass(db);
					if(check(resolved)){
						return;
					}
					throw new IllegalStateException("Invalid arguments: " + errMsg(resolved));
				};
			}
		}
		
		static ArgCheck rawAny(ArgCheck.RawCheck... anyOf){
			var args = List.of(anyOf);
			return (type, db) -> {
				var resolved = type.getTypeClass(db);
				for(var arg : args){
					if(arg.check(resolved)){
						return;
					}
				}
				throw new IllegalStateException("No matching type requirement:\n\t" + Iters.from(args).joinAsStr("\n\t", c -> c.errMsg(resolved)));
			};
		}
		
		void check(IOType type, IOTypeDB db);
	}
	
	private final Consumer<Class<?>> rawCheck;
	private final List<ArgCheck>     argChecks;
	
	public TypeCheck(Class<?> rawType, ArgCheck... argChecks){
		this(t -> {
			if(!t.equals(rawType)){
				throw new ClassCastException(rawType.getTypeName() + " is not " + t.getTypeName());
			}
		}, argChecks);
	}
	public TypeCheck(Consumer<Class<?>> rawCheck, ArgCheck... argChecks){
		this.rawCheck = rawCheck;
		this.argChecks = List.of(argChecks);
	}
	
	public void ensureValid(IOType type, IOTypeDB db){
		try{
			rawCheck.accept(type.getTypeClass(db));
			var args = IOType.getArgs(type);
			if(args.size() != argChecks.size()){
				throw new IllegalArgumentException(
					"Argument count in " + type + " should be " + argChecks.size() + " but is " + args.size()
				);
			}
			
			var errs = new LinkedList<Throwable>();
			
			for(int i = 0; i<argChecks.size(); i++){
				var typ = args.get(i);
				var ch  = argChecks.get(i);
				
				try{
					ch.check(typ, db);
				}catch(Throwable e){
					errs.add(new IllegalArgumentException("Argument " + typ + " at " + i + " is not valid!", e));
				}
			}
			
			if(!errs.isEmpty()){
				var err = new IllegalArgumentException("Generic arguments are invalid");
				errs.forEach(err::addSuppressed);
				throw err;
			}
		}catch(Throwable e){
			throw new InvalidGenericArgument(type.toShortString() + " is not valid!", e);
		}
	}
}
