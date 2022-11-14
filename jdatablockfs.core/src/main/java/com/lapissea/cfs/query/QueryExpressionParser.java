package com.lapissea.cfs.query;

import com.lapissea.cfs.io.bit.EnumUniverse;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.lang.reflect.Array;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.Double.parseDouble;
import static java.lang.Long.parseLong;

public class QueryExpressionParser{
	
	private interface Matched{
		List<String> matches();
	}
	
	private enum Connector implements Matched{
		AND(Check.And::new, "&&", "&", "and"),
		OR(Check.Or::new, "||", "|", "or");
		
		final BiFunction<Check, Check, Check> ctor;
		final List<String>                    matches;
		
		Connector(BiFunction<Check, Check, Check> ctor, String... matches){
			this.ctor=ctor;
			this.matches=List.of(matches);
		}
		@Override
		public List<String> matches(){
			return matches;
		}
		Check gnu(Check l, Check r){
			return ctor.apply(l, r);
		}
	}
	
	private enum Comparison implements Matched{
		EQUALS(Check.Equals::new, "==", "=", "is", "equals"),
		GREATER(Check.GreaterThan::new, ">"),
		GREATER_OR_EQUAL((src, arg)->new Check.Or(new Check.GreaterThan(src, arg), new Check.Equals(src, arg)), ">="),
		LESSER(Check.LessThan::new, "<"),
		LESSER_OR_EQUAL((src, arg)->new Check.Or(new Check.LessThan(src, arg), new Check.Equals(src, arg)), "<="),
//		IN(Check.In::new, "in"),
		;
		
		final BiFunction<String, ArgSource, Check> ctor;
		final List<String>                         matches;
		Comparison(BiFunction<String, ArgSource, Check> ctor, String... matches){
			this.ctor=ctor;
			this.matches=List.of(matches);
		}
		@Override
		public List<String> matches(){
			return matches;
		}
	}
	
	private sealed interface ArgSource{
		record Root() implements ArgSource{
			@Override
			public String toString(){
				return "args";
			}
		}
		
		record GetArray(int index, ArgSource source) implements ArgSource{
			@Override
			public String toString(){
				return source+"["+index+"]";
			}
		}
		
		record Literal(Object value) implements ArgSource{
			@Override
			public String toString(){
				return value+"";
			}
		}
	}
	
	private interface ArgContain{
		ArgSource arg();
	}
	
	private interface FieldRef{
		String name();
	}
	
	private sealed interface Check{
		record And(Check l, Check r) implements Check{
			@Override
			public String toString(){
				return "("+l+" && "+r+")";
			}
		}
		
		record Or(Check l, Check r) implements Check{
			@Override
			public String toString(){
				return "("+l+" || "+r+")";
			}
		}
		
		record Not(Check check) implements Check{
			@Override
			public String toString(){
				return "!"+check;
			}
		}
		
		record Equals(String name, ArgSource arg) implements Check, ArgContain, FieldRef{
			@Override
			public String toString(){
				return name+" == "+arg;
			}
		}
		
		record GreaterThan(String name, ArgSource arg) implements Check, ArgContain, FieldRef{
			@Override
			public String toString(){
				return name+" > "+arg;
			}
		}
		
		record LessThan(String name, ArgSource arg) implements Check, ArgContain, FieldRef{
			@Override
			public String toString(){
				return name+" < "+arg;
			}
		}
	}
	
	private record FilterQuery<T>(Class<T> type, String expression, int hash){
		private FilterQuery(Class<T> type, String expression){
			this(type, expression, (31+type.hashCode())*31+expression.hashCode());
		}
		@Override
		public int hashCode(){
			return hash;
		}
	}
	
	public record FilterResult<T>(Set<String> readFields, Consumer<T> argCheck, BiPredicate<Object[], T> filter){}
	
	
	private static final Map<FilterQuery<?>, FilterResult<?>> FILTER_CACHE=new ConcurrentHashMap<>();
	
	@SuppressWarnings("unchecked")
	public static <T> FilterResult<T> filter(Class<T> type, String expression){
		return (FilterResult<T>)FILTER_CACHE.computeIfAbsent(new FilterQuery<>(type, expression), QueryExpressionParser::parse);
	}
	
	private static <T> FilterResult<T> parse(FilterQuery<T> filterQuery){
		Check check=expressionToCheck(filterQuery.type, filterQuery.expression);
		
		var args  =deep(check).filter(c->c instanceof ArgContain).map(c->((ArgContain)c).arg()).collect(Collectors.toSet());
		var fields=deep(check).filter(c->c instanceof FieldRef).map(c->((FieldRef)c).name()).collect(Collectors.toUnmodifiableSet());
		
		Consumer<T>              argCheck =generateArgCheck(args);
		BiPredicate<Object[], T> predicate=generateFilter(args, check);
		
		return new FilterResult<>(Set.copyOf(fields), argCheck, predicate);
	}
	
	private static <T> BiPredicate<Object[], T> generateFilter(Set<ArgSource> argSet, Check check){
		return (args, obj)->{
			return reflectionCheck(args, obj, check);
		};
	}
	
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static <T> boolean reflectionCheck(Object[] args, T obj, Check check){
		return switch(check){
			case Check.And and -> {
				var l=reflectionCheck(args, obj, and.l);
				var r=reflectionCheck(args, obj, and.r);
				yield l&&r;
			}
			case Check.Or or -> {
				var l=reflectionCheck(args, obj, or.l);
				if(l) yield true;
				var r=reflectionCheck(args, obj, or.r);
				yield r;
			}
			case Check.Not not -> !reflectionCheck(args, obj, not.check);
			case Check.Equals equals -> {
				Object arg=getArg(args, equals.arg);
				if(arg==null) yield obj==null;
				IOField field =Struct.ofUnknown(obj.getClass()).getFields().byName(equals.name).orElseThrow();
				var     typ   =field.getAccessor().getType();
				var     argTyp=arg.getClass();
				
				if(arg instanceof Number num&&(typ==byte.class||typ==short.class||typ==int.class||typ==long.class||typ==float.class||typ==double.class)){
					var val=(Number)field.get(null, (IOInstance)obj);
					if(val instanceof Double d){
						yield d==num.doubleValue();
					}
					if(val instanceof Float f){
						yield f==num.floatValue();
					}
					yield val.longValue()==num.longValue();
				}
				
				if(!UtilL.instanceOf(argTyp, typ)){
					throw new ClassCastException(obj+" not compatible with "+field);
				}
				var val=field.get(null, (IOInstance)obj);
				yield arg.equals(val);
			}
			case Check.GreaterThan equals -> {
				Number  arg=(Number)getArg(args, equals.arg);
				IOField f  =Struct.ofUnknown(obj.getClass()).getFields().byName(equals.name).orElseThrow();
				Number  val=(Number)f.get(null, (IOInstance)obj);
				yield val.doubleValue()>arg.doubleValue();
			}
			case Check.LessThan equals -> {
				Number  arg=(Number)getArg(args, equals.arg);
				IOField f  =Struct.ofUnknown(obj.getClass()).getFields().byName(equals.name).orElseThrow();
				Number  val=(Number)f.get(null, (IOInstance)obj);
				yield val.doubleValue()<arg.doubleValue();
			}
		};
	}
	
	private static Object getArg(Object[] args, ArgSource arg){
		return switch(arg){
			case ArgSource.GetArray getArray -> Array.get(getArg(args, getArray.source), getArray.index);
			case ArgSource.Root root -> args;
			case ArgSource.Literal literal -> literal.value;
		};
	}
	
	private static Stream<Check> deep(Check check){
		return switch(check){
			case Check.And c -> Stream.of(Stream.of(c), deep(c.l), deep(c.r)).flatMap(s->s);
			case Check.Or c -> Stream.of(Stream.of(c), deep(c.l), deep(c.r)).flatMap(s->s);
			case Check.Equals c -> Stream.of(c);
			case Check.GreaterThan c -> Stream.of(c);
			case Check.LessThan c -> Stream.of(c);
			case Check.Not c -> Stream.of(Stream.of(c), deep(c.check)).flatMap(s->s);
		};
	}
	private static Optional<Class<?>> argType(ArgSource arg){
		return switch(arg){
			case ArgSource.GetArray getArray -> Optional.empty();
			case ArgSource.Root root -> throw new UnsupportedOperationException();
			case ArgSource.Literal literal -> Optional.of(literal.value.getClass());
		};
	}
	
	private static <T> Consumer<T> generateArgCheck(Set<ArgSource> args){
		return t->{};
	}
	
	private static class Reader{
		private       int    pos;
		private final String str;
		private Reader(String str){
			this.str=str;
		}
		
		private interface Test{
			boolean test(int i, char c);
		}
		
		private String advance(Test predicate){
			var start=pos;
			for(int i=pos;i<str.length();i++){
				var c=str.charAt(i);
				if(predicate.test(i, c)){
					continue;
				}
				pos=i;
				break;
			}
			if(start==pos) return null;
			return str.substring(start, pos);
		}
		private String field(){
			var start=pos;
			return advance((i, c)->{
				if(i==start){
					return Character.isJavaIdentifierStart(c);
				}
				return Character.isJavaIdentifierPart(c);
			});
		}
		public String squiggly(){
			skipWhite();
			if(str.charAt(pos)!='{') return null;
			pos++;
			var inside=advance((i, c)->c!='}');
			pos++;
			return inside;
		}
		
		public String brace(){
			skipWhite();
			if(str.charAt(pos)!='(') return null;
			pos++;
			
			int[] depth={1};
			return advance((i, c)->{
				if(c=='(') depth[0]++;
				if(c==')') depth[0]--;
				return depth[0]>0;
			});
		}
		
		public Number number(){
			var hex=regex(Pattern.compile("^[-+]?0[xX][0-9a-fA-F_]+"));
			if(hex!=null){
				hex=cleanNumber(hex, "0x");
				return parseLong(hex, 16);
			}
			var bin=regex(Pattern.compile("^[-+]??0[bB][0-1_]+"));
			if(bin!=null){
				bin=cleanNumber(bin, "0b");
				return parseLong(bin.replace("_", ""), 2);
			}
			var dec=regex(Pattern.compile("^[-+]??[0-9_]*\\.?[0-9_]*"));
			if(dec!=null){
				dec=cleanNumber(dec, null);
				if(dec.contains(".")){
					return parseDouble(dec.replace("_", ""));
				}
				return parseLong(dec);
			}
			
			return null;
		}
		
		private static String cleanNumber(String num, String prefix){
			if(num.charAt(0)=='+') num=num.substring(1);
			if(prefix!=null){
				if(num.charAt(0)=='-'){
					num="-"+num.substring(prefix.length()+1);
				}else{
					num=num.substring(prefix.length());
				}
			}
			num=num.replace("_", "");
			return num;
		}
		
		private String regex(Pattern pattern){
			skipWhite();
			var matcher=pattern.matcher(str.subSequence(pos, str.length()));
			if(!matcher.find()) return null;
			var result=matcher.group();
			if(result.isEmpty()) return null;
			pos+=result.length();
			return result;
		}
		
		private <T extends Enum<T>&Matched> T match(Class<T> toCheck){
			return match(toCheck, true);
		}
		private <T extends Enum<T>&Matched> T match(Class<T> toCheck, boolean require){
			var universe=EnumUniverse.of(toCheck);
			var result=universe.stream().flatMap(c->c.matches().stream().map(m->Map.entry(c, m)))
			                   .sorted(Comparator.comparingInt(e->-e.getValue().length()))
			                   .filter(e->match(e.getValue())).findFirst().map(Map.Entry::getKey);
			if(require&&result.isEmpty()){
				throw new RuntimeException(
					"Expected any of: "+universe.stream().flatMap(c->c.matches().stream())
					                            .collect(Collectors.joining(", "))
				);
			}
			return result.orElse(null);
		}
		
		private boolean not(){
			return match("!", "not");
		}
		
		private boolean match(String... matches){
			for(var match : matches){
				if(match(match)){
					return true;
				}
			}
			return false;
		}
		private boolean match(String match){
			skipWhite();
			if(startsWith(match)){
				boolean validEnd;
				if(Character.isLetter(match.charAt(match.length()-1))){
					var c=str.charAt(pos+match.length());
					validEnd=Character.isWhitespace(c)||List.of('(', ')', '[', ']', '{', '}', '<', '>', '!', '?', '&', '|', '=').contains(c);
				}else validEnd=true;
				if(validEnd){
					pos+=match.length();
					return true;
				}
			}
			return false;
		}
		
		private boolean startsWith(String match){
			return str.regionMatches(true, pos, match, 0, match.length());
		}
		
		private void skipWhite(){
			for(int i=pos;i<str.length();i++){
				if(Character.isWhitespace(str.charAt(i))) pos++;
				else break;
			}
		}
		@Override
		public String toString(){
			return str.substring(pos);
		}
	}
	
	private static class Parser{
		
		private final Class<?> type;
		private final Reader   reader;
		private       boolean  notNext=false;
		
		private Parser(Class<?> type, Reader reader){
			this.type=type;
			this.reader=reader;
		}
		
		Check parse(){
			
			Check check=null;
			while(true){
				reader.skipWhite();
				if(reader.str.length()==reader.pos){
					if(check==null) throw new RuntimeException("Unexpected end");
					return check;
				}
				
				if(!notNext&&reader.not()){
					notNext=true;
					continue;
				}
				
				if(check!=null){
					var con=reader.match(Connector.class);
					var r  =requireNextCheck();
					check=con.gnu(check, r);
				}else{
					check=requireNextCheck();
				}
			}
		}
		
		private Check requireNextCheck(){
			return Objects.requireNonNull(nextCheck(), "Unexpected end");
		}
		
		private Check nextCheck(){
			var check=nextCheck0();
			if(check==null) return null;
			if(notNext){
				notNext=false;
				check=new Check.Not(check);
			}
			return check;
		}
		private Check nextCheck0(){
			var brace=reader.brace();
			if(brace!=null){
				return expressionToCheck(type, brace);
			}
			
			var fieldName=reader.field();
			if(fieldName!=null){
				var comp  =reader.match(Comparison.class);
				var source=source();
				checkComparison(fieldName, source);
				return comp.ctor.apply(fieldName, source);
			}
			
			throw new NotImplementedException();
		}
		
		private void checkComparison(String fieldName, ArgSource source){
			var type   =fieldType(fieldName);
			var argType=argType(source);
			argType.ifPresent(typ->{
				if(UtilL.instanceOf(typ, type)){
					throw new ClassCastException("Cannot cast "+typ.getName()+" to "+type.getName());
				}
			});
		}
		
		private Class<?> fieldType(String fieldName){
			if(IOInstance.isInstance(type)){
				return Struct.ofUnknown(type).getFields().byName(fieldName)
				             .orElseThrow(()->noField(fieldName))
				             .getAccessor().getType();
			}
			
			var field=Arrays.stream(type.getFields()).filter(f->f.getName().equals(fieldName)).findAny();
			if(field.isPresent()){
				return field.get().getType();
			}
			
			try{
				var getter=type.getMethod("get"+TextUtil.firstToUpperCase(fieldName));
				if(getter.getReturnType()!=void.class){
					return getter.getReturnType();
				}
			}catch(NoSuchMethodException ignored){}
			
			throw noField(fieldName);
		}
		
		private RuntimeException noField(String fieldName){
			return new RuntimeException(fieldName+" does not exist in "+type.getName());
		}
		
		private ArgSource source(){
			var argStr=reader.squiggly();
			if(argStr!=null){
				int index=Integer.parseInt(argStr);
				return new ArgSource.GetArray(index, new ArgSource.Root());
			}
			var num=reader.number();
			if(num!=null){
				return new ArgSource.Literal(num);
			}
			
			throw new RuntimeException("???");
		}
	}
	
	private static Check expressionToCheck(Class<?> type, String expression){
		return new Parser(type, new Reader(expression)).parse();
	}
}
