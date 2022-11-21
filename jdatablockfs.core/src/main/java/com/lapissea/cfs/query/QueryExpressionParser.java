package com.lapissea.cfs.query;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.InvalidQueryString;
import com.lapissea.cfs.io.bit.EnumUniverse;
import com.lapissea.cfs.logging.Log;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.SupportedPrimitive;
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
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.Double.parseDouble;
import static java.lang.Long.parseLong;

public class QueryExpressionParser{
	
	private interface Matched{
		List<String> matches();
	}
	
	private enum Modifier implements Matched{
		MODULUS(DataSource.Modulus::new, "%", "mod"),
		ADD(DataSource.Add::new, "+", "add"),
//		SUBTRACT(DataSource.Subtract::new, "-", "sub"),
//		DIVIDE(DataSource.Divide::new, "/", "div"),
//		MULTIPLY(DataSource.Multiply::new, "-", "mul"),
//		POWER(DataSource.Power::new, "^", "pow"),
		;
		
		final BiFunction<DataSource, DataSource, DataSource> ctor;
		final List<String>                                   matches;
		
		Modifier(BiFunction<DataSource, DataSource, DataSource> ctor, String... matches){
			this.ctor=ctor;
			this.matches=List.of(matches);
		}
		@Override
		public List<String> matches(){
			return matches;
		}
		DataSource gnu(DataSource l, DataSource r){
			return ctor.apply(l, r);
		}
	}
	
	private enum Connector implements Matched{
		AND(Check.And::new, "&&", "&", "and"),
		OR(Check.Or::new, "||", "|", "or"),
		;
		
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
		NOT_EQUALS((src, arg)->negate(new Check.Equals(src, arg)), "!=", "is not"),
		GREATER(Check.GreaterThan::new, ">"),
		GREATER_OR_EQUAL((src, arg)->negate(new Check.LessThan(src, arg)), ">="),
		LESSER(Check.LessThan::new, "<"),
		LESSER_OR_EQUAL((src, arg)->negate(new Check.GreaterThan(src, arg)), "<="),
		IN(Check.In::new, "in"),
		;
		
		final BiFunction<DataSource, DataSource, Check> ctor;
		final List<String>                              matches;
		Comparison(BiFunction<DataSource, DataSource, Check> ctor, String... matches){
			this.ctor=ctor;
			this.matches=List.of(matches);
		}
		@Override
		public List<String> matches(){
			return matches;
		}
	}
	
	private sealed interface DataSource{
		record Root() implements DataSource{
			@Override
			public String toString(){
				return "ARG!";
			}
		}
		
		record GetArray(int index, DataSource source) implements DataSource{
			@Override
			public String toString(){
				return source+"["+index+"]";
			}
		}
		
		record Literal(Object value) implements DataSource{
			@Override
			public String toString(){
				return switch(value){
					case String s -> "'"+s+"'";
					case Float s -> s+"F";
					case Double s -> s+"D";
					case Long s -> s+"L";
					default -> value+"";
				};
			}
		}
		
		record Field(Function<Object, Object> getter, Class<?> type, String name) implements DataSource{
			@Override
			public String toString(){
				return "#"+name;
			}
		}
		
		record Modulus(DataSource src, DataSource mod) implements DataSource{
			@Override
			public String toString(){
				return src+"%"+mod;
			}
		}
		
		record Add(DataSource l, DataSource r) implements DataSource{
			@Override
			public String toString(){
				return l+" + "+r;
			}
		}
	}
	
	private interface DataSourceContain{
		Stream<DataSource> sources();
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
				return "!("+check+")";
			}
		}
		
		record Equals(DataSource field, DataSource arg) implements Check, DataSourceContain{
			@Override
			public String toString(){
				return field+" == "+arg;
			}
			@Override
			public Stream<DataSource> sources(){
				return Stream.of(field, arg);
			}
		}
		
		record GreaterThan(DataSource field, DataSource arg) implements Check, DataSourceContain{
			@Override
			public String toString(){
				return field+" > "+arg;
			}
			@Override
			public Stream<DataSource> sources(){
				return Stream.of(field, arg);
			}
		}
		
		record LessThan(DataSource field, DataSource arg) implements Check, DataSourceContain{
			@Override
			public String toString(){
				return field+" < "+arg;
			}
			@Override
			public Stream<DataSource> sources(){
				return Stream.of(field, arg);
			}
		}
		
		record In(DataSource needle, DataSource hay) implements Check, DataSourceContain{
			@Override
			public String toString(){
				return "("+needle+" in "+hay+")";
			}
			@Override
			public Stream<DataSource> sources(){
				return Stream.of(needle, hay);
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
		Check compiledCheck=expressionToCheck(filterQuery.type, filterQuery.expression, null);
		
		Log.trace("Compiled check for {}#cyan - \"{}#red\": {}#blue",
		          filterQuery.type.getSimpleName(), filterQuery.expression, compiledCheck);
		
		var fields=deep(compiledCheck).filter(c->c instanceof DataSourceContain)
		                              .flatMap(c->((DataSourceContain)c).sources())
		                              .flatMap(QueryExpressionParser::deep)
		                              .filter(s->s instanceof DataSource.Field)
		                              .map(s->((DataSource.Field)s))
		                              .map(f->f.name)
		                              .collect(Collectors.toSet());
		
		Consumer<T>              argCheck =t->{};
		BiPredicate<Object[], T> predicate=generateFilter(compiledCheck);
		
		return new FilterResult<>(Set.copyOf(fields), argCheck, predicate);
	}
	
	private static <T> BiPredicate<Object[], T> generateFilter(Check check){
		return (args, obj)->{
			return reflectionCheck(args, obj, check);
		};
	}
	
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
				Object val=getArg(args, obj, equals.field);
				Object arg=getArg(args, obj, equals.arg);
				if(arg==null) yield val==null;
				
				if(arg instanceof Number argN&&val instanceof Number valN){
					if(valN instanceof Double d){
						yield d==argN.doubleValue();
					}
					if(valN instanceof Float f){
						yield f==argN.floatValue();
					}
					yield valN.longValue()==argN.longValue();
				}
				
				if(!UtilL.instanceOf(arg.getClass(), val.getClass())){
					throw new ClassCastException(obj+" not compatible with "+equals.field);
				}
				yield arg.equals(val);
			}
			case Check.GreaterThan equals -> {
				var val=(Number)getArg(args, obj, equals.field);
				var arg=(Number)getArg(args, obj, equals.arg);
				yield val.doubleValue()>arg.doubleValue();
			}
			case Check.LessThan equals -> {
				var val=(Number)getArg(args, obj, equals.field);
				var arg=(Number)getArg(args, obj, equals.arg);
				yield val.doubleValue()<arg.doubleValue();
			}
			case Check.In in -> {
				var needle=getArg(args, obj, in.needle);
				var hay   =getArg(args, obj, in.hay);
				if(hay==null) yield false;
				if(hay instanceof String str){
					yield str.contains((CharSequence)needle);
				}
				
				if(hay instanceof List<?> list){
					yield list.contains(needle);
				}
				
				if(hay.getClass().isArray()){
					var size=Array.getLength(hay);
					for(int i=0;i<size;i++){
						var el=Array.get(hay, i);
						if(Objects.equals(hay, needle)){
							yield true;
						}
					}
				}
				
				yield false;
			}
		};
	}
	
	private static <T> Object getArg(Object[] args, T obj, DataSource arg){
		return switch(arg){
			case DataSource.GetArray getArray -> Array.get(getArg(args, obj, getArray.source), getArray.index);
			case DataSource.Root root -> args;
			case DataSource.Literal literal -> literal.value;
			case DataSource.Field field -> field.getter.apply(obj);
			case DataSource.Modulus modulus -> {
				var src=getArg(args, obj, modulus.src);
				var mod=((Number)getArg(args, obj, modulus.mod)).intValue();
				yield switch(src){
					case Integer n -> n%mod;
					case Long n -> n%mod;
					case Double n -> n%mod;
					case Float n -> n%mod;
					default -> throw new IllegalStateException("Unexpected value: "+src);
				};
			}
			case DataSource.Add add -> {
				var l=(Number)getArg(args, obj, add.l);
				var r=(Number)getArg(args, obj, add.r);
				yield addAB(l, r);
			}
		};
	}
	
	private static Class<?> addTyp(Class<?> l, Class<?> r){
		var lt=SupportedPrimitive.get(l).orElseThrow();
		var rt=SupportedPrimitive.get(r).orElseThrow();
		if(lt.getType()!=rt.getType()) throw new NotImplementedException();
		return lt.getType();
	}
	private static Object addAB(Number l, Number r){
		return switch(l){
			case Integer a -> switch(r){
				case Integer b -> a+b;
				case Long b -> a+b;
				case Double b -> a+b;
				case Float b -> a+b;
				default -> throw new IllegalStateException("Unexpected value: "+l);
			};
			case Long a -> switch(r){
				case Integer b -> a+b;
				case Long b -> a+b;
				case Double b -> a+b;
				case Float b -> a+b;
				default -> throw new IllegalStateException("Unexpected value: "+l);
			};
			case Double a -> switch(r){
				case Integer b -> a+b;
				case Long b -> a+b;
				case Double b -> a+b;
				case Float b -> a+b;
				default -> throw new IllegalStateException("Unexpected value: "+l);
			};
			case Float a -> switch(r){
				case Integer b -> a+b;
				case Long b -> a+b;
				case Double b -> a+b;
				case Float b -> a+b;
				default -> throw new IllegalStateException("Unexpected value: "+l);
			};
			default -> throw new IllegalStateException("Unexpected value: "+l);
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
			case Check.In c -> Stream.of(c);
		};
	}
	private static Stream<DataSource> deep(DataSource data){
		return Stream.concat(Stream.of(data), switch(data){
			case DataSource.Add c -> Stream.concat(deep(c.l), deep(c.r));
			case DataSource.GetArray c -> deep(c.source);
			case DataSource.Modulus c -> Stream.concat(deep(c.src), deep(c.mod));
			case DataSource.Literal c -> Stream.<DataSource>empty();
			case DataSource.Field c -> Stream.<DataSource>empty();
			case DataSource.Root c -> Stream.<DataSource>empty();
		});
	}
	private static Optional<Class<?>> argType(DataSource arg){
		return switch(arg){
			case DataSource.GetArray getArray -> Optional.empty();
			case DataSource.Root root -> throw new UnsupportedOperationException();
			case DataSource.Literal literal -> Optional.of(literal.value.getClass());
			case DataSource.Field field -> Optional.of(field.type);
			case DataSource.Modulus modulus -> argType(modulus.src);
			case DataSource.Add add -> {
				var l=argType(add.l);
				var r=argType(add.r);
				if(l.isPresent()&&r.isPresent()){
					yield Optional.of(addTyp(l.get(), r.get()));
				}
				yield Optional.empty();
			}
		};
	}
	
	private static Check negate(Check check){
		return switch(check){
			case Check.Not not -> not.check;
			default -> new Check.Not(check);
		};
	}
	
	private static <T> Consumer<T> generateArgCheck(Set<DataSource> args){
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
			for(int i=pos;;i++){
				if(i>=str.length()){
					pos=i;
					break;
				}
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
			return inside==null?"":inside;
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
		
		public String string(){
			skipWhite();
			if(str.charAt(pos)!='\'') return null;
			pos++;
			var inside=advance((i, c)->c!='\'');
			pos++;
			return inside==null?"":inside;
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
				throw new InvalidQueryString(
					"Expected any "+toCheck.getSimpleName()+
					" ("+universe.stream().flatMap(c->c.matches().stream()).collect(Collectors.joining(", "))+")"+
					atTemplate(pos));
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
		
		InvalidQueryString nextWordBad(String message){
			var    pos =this.pos;
			var    word=regex(Pattern.compile("^\\S+"));
			String msg;
			if(word==null) msg="Unexpected end. "+message;
			else msg="\""+word+"\" "+message;
			
			throw new InvalidQueryString(msg+atTemplate(pos));
		}
		
		private String atTemplate(int pos){
			return "\n"+
			       str+"\n"+
			       " ".repeat(pos)+"^";
		}
	}
	
	private static class Parser{
		
		private final Class<?> type;
		private final Reader   reader;
		private       boolean  notNext=false;
		private final int[]    argCounter;
		
		private Parser(Class<?> type, Reader reader, int[] argCounter){
			this.type=type;
			this.reader=reader;
			this.argCounter=argCounter;
		}
		
		Check parse(){
			
			Check check=null;
			while(true){
				reader.skipWhite();
				if(reader.str.length()==reader.pos){
					if(check==null) throw new InvalidQueryString("Unexpected end");
					return check;
				}
				
				if(!notNext&&reader.not()){
					notNext=true;
					continue;
				}
				
				if(check!=null){
					var con=reader.match(Connector.class);
					var r  =check();
					check=con.gnu(check, r);
				}else{
					check=check();
				}
			}
		}
		
		private Check check(){
			var check=nextCheck();
			if(check==null) return null;
			if(notNext){
				notNext=false;
				check=negate(check);
			}
			return check;
		}
		private Check nextCheck(){
			var brace=reader.brace();
			if(brace!=null){
				return expressionToCheck(type, brace, argCounter);
			}
			
			var field=readField();
			if(field!=null){
				var comp  =reader.match(Comparison.class);
				var source=source();
				checkComparison(field, source);
				return comp.ctor.apply(field, source);
			}
			
			var str=reader.string();
			if(str!=null){
				if(Comparison.IN.matches().stream().noneMatch(reader::match)){
					throw reader.nextWordBad(
						"Expected "+Comparison.IN.matches().stream().collect(Collectors.joining(" or "))+
						" after left hand string literal"
					);
				}
				var pos   =reader.pos;
				var source=source();
				if(deep(source).noneMatch(s->s instanceof DataSource.Field)){
					reader.pos=pos;
					throw reader.nextWordBad("No field on left or right side of IN");
				}
				
				return new Check.In(new DataSource.Literal(str), source);
			}
			
			throw reader.nextWordBad("Unexpected token");
		}
		
		private DataSource readField(){
			var name=reader.field();
			if(name==null) return null;
			DataSource field=findField(name);
			return modifiedData(field);
		}
		
		private void checkComparison(DataSource l, DataSource r){
			argType(l).ifPresent(lType->{
				argType(r).ifPresent(rType->{
					if(lType!=rType&&UtilL.instanceOf(lType, rType)){
						throw new ClassCastException("Cannot cast "+lType+" to "+rType);
					}
				});
			});
		}
		
		@SuppressWarnings({"rawtypes", "unchecked"})
		private DataSource.Field findField(String fieldName){
			if(IOInstance.isInstance(type)){
				IOField field=Struct.ofUnknown(type).getFields().byName(fieldName)
				                    .orElseThrow(()->noField(fieldName));
				if(Utils.isVirtual(field, null)){
					throw new NotImplementedException("Virtual field access not implemented");
				}
				var acc=field.getAccessor();
				
				return new DataSource.Field(obj->acc.get(null, (IOInstance)obj), acc.getType(), acc.getName());
			}
			
			var fieldO=Arrays.stream(type.getFields()).filter(f->f.getName().equals(fieldName)).findAny();
			if(fieldO.isPresent()){
				var field=fieldO.get();
				return new DataSource.Field(obj->{
					try{
						return field.get(obj);
					}catch(IllegalAccessException e){
						throw new RuntimeException(e);
					}
				}, field.getType(), field.getName());
			}
			
			try{
				var getter=type.getMethod("get"+TextUtil.firstToUpperCase(fieldName));
				if(getter.getReturnType()!=void.class){
					return new DataSource.Field(obj->{
						try{
							return getter.invoke(obj);
						}catch(ReflectiveOperationException e){
							throw new RuntimeException(e);
						}
					}, getter.getReturnType(), fieldName);
				}
			}catch(NoSuchMethodException ignored){}
			
			throw noField(fieldName);
		}
		
		private RuntimeException noField(String fieldName){
			return new InvalidQueryString(fieldName+" does not exist in "+type.getName());
		}
		
		private DataSource modifiedData(DataSource data){
			while(true){
				var mod=reader.match(Modifier.class, false);
				if(mod==null) break;
				var src=nextSource();
				data=mod.gnu(data, src);
			}
			return data;
		}
		
		private DataSource source(){
			var soruce=nextSource();
			return modifiedData(soruce);
		}
		private DataSource nextSource(){
			var argStr=reader.squiggly();
			if(argStr!=null){
				int index;
				if(argStr.isEmpty()){
					index=argCounter[0]++;
				}else{
					index=Integer.parseInt(argStr);
				}
				return new DataSource.GetArray(index, new DataSource.Root());
			}
			var num=reader.number();
			if(num!=null){
				return new DataSource.Literal(num);
			}
			var str=reader.string();
			if(str!=null){
				return new DataSource.Literal(str);
			}
			var fieldName=reader.field();
			if(fieldName!=null){
				return findField(fieldName);
			}
			
			throw reader.nextWordBad("is not a valid literal or value source");
		}
	}
	
	private static Check expressionToCheck(Class<?> type, String expression, int[] argCounter){
		if(argCounter==null) argCounter=new int[1];
		return new Parser(type, new Reader(expression), argCounter).parse();
	}
}
