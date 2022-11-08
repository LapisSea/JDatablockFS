package com.lapissea.cfs.run;

import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.exceptions.MalformedStruct;
import com.lapissea.cfs.io.bit.EnumUniverse;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.io.instancepipe.FixedStructPipe;
import com.lapissea.cfs.io.instancepipe.StandardStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.ObjectID;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.objects.collections.ContiguousIOList;
import com.lapissea.cfs.objects.collections.HashIOMap;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.objects.collections.LinkedIOList;
import com.lapissea.cfs.objects.text.AutoText;
import com.lapissea.cfs.tools.utils.ToolUtils;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.StagedInit;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.TypeLink;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.annotations.IOCompression;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IOValue;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import static com.lapissea.cfs.type.StagedInit.STATE_DONE;
import static com.lapissea.cfs.type.field.annotations.IOCompression.Type.RLE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.testng.AssertJUnit.assertEquals;

public class GeneralTypeHandlingTests{
	
	public static class Deps extends IOInstance.Managed<Deps>{
		
		@IOValue
		@IODependency("b")
		public int a;
		
		@IOValue
		@IODependency("c")
		public int b;
		
		@IOValue
		public int c;
		
		@IOValue
		@IODependency({"c", "b"})
		public int d;
	}
	
	public static class Arr extends IOInstance.Managed<Arr>{
		@IOValue
		public float[] arr;
	}
	
	@DataProvider(name="genericObjects")
	static Object[][] genericObjects(){
		var deps=new Deps();
		deps.a=1;
		deps.b=2;
		deps.c=3;
		deps.d=4;
		var arr=new Arr();
		arr.arr=new float[]{1, 2, 3, 4, 5};
		return new Object[][]{{Dummy.first()}, {deps}, {arr}};
	}
	
	@Test(dataProvider="genericObjects")
	<T extends IOInstance<T>> void genericStorage(T obj) throws IOException{
		TestUtils.testCluster(TestInfo.of(obj), ses->{
			var ls=ses.getRootProvider().<IOList<GenericContainer<?>>>builder().withType(TypeLink.ofFlat(
				LinkedIOList.class,
				GenericContainer.class, Object.class
			)).withId("list").request();
			
			var c=new GenericContainer<>(obj);
			ls.clear();
			ls.add(c);
			var read=ls.get(0).value;
			assertEquals(obj, read);
		});
	}
	
	@Test
	void genericTest() throws IOException{
		TestUtils.testChunkProvider(TestInfo.of(), provider->{
			var pipe=StandardStructPipe.of(GenericContainer.class);
			
			var chunk=AllocateTicket.bytes(64).submit(provider);
			
			var container=new GenericContainer<>();
			
			container.value=new Dummy(123);
			
			pipe.write(chunk, container);
			var read=pipe.readNew(chunk, null);
			
			assertEquals(container, read);
			
			
			container.value="This is a test.";
			
			pipe.write(chunk, container);
			read=pipe.readNew(chunk, null);
			
			assertEquals(container, read);
		});
	}
	
	@Test(dataProvider="types")
	<T extends IOInstance<T>> void checkIntegrity(Struct<T> struct) throws IOException{
		if(struct instanceof Struct.Unmanaged){
			return;
		}
		
		StructPipe<T> pipe;
		try{
			pipe=StandardStructPipe.of(struct, STATE_DONE);
		}catch(MalformedStruct|StagedInit.WaitException ignored){
			pipe=null;
		}
		if(pipe!=null){
			pipe.checkTypeIntegrity(struct.make());
		}
		try{
			pipe=FixedStructPipe.of(struct, STATE_DONE);
		}catch(MalformedStruct|StagedInit.WaitException ignored){
			pipe=null;
		}
		if(pipe!=null){
			pipe.checkTypeIntegrity(struct.make());
		}
	}
	
	@DataProvider(name="types")
	private static Object[][] types(){
		return Stream.of(
			             Reference.class,
			             AutoText.class,
			             Cluster.class,
			             ContiguousIOList.class,
			             LinkedIOList.class,
			             HashIOMap.class,
			             BooleanContainer.class,
			             IntContainer.class,
			             LongContainer.class,
			             Dummy.class
		             ).flatMap(p->Stream.concat(Stream.of(p), Arrays.stream(p.getDeclaredClasses())))
		             .filter(IOInstance::isInstance)
		             .map(Struct::ofUnknown)
		             .map(o->new Object[]{o})
		             .toArray(Object[][]::new);
	}
	
	
	public enum RandomEnum{A, B, C}
	
	public static class EnumContainer extends IOInstance.Managed<EnumContainer>{
		@IOValue
		RandomEnum r=RandomEnum.A;
	}
	
	public static byte[] make() throws IOException{
		var data=MemoryData.builder().build();
		Cluster.init(data).getRootProvider().request("hello!", EnumContainer.class);
		return data.readAll();
	}
	public static void use(byte[] data) throws IOException{
		var cluster=new Cluster(MemoryData.builder().withRaw(data).build());
		
		var r        =cluster.getRootProvider().builder().withId("hello!").withGenerator(()->{throw new RuntimeException();});
		var container=(IOInstance<?>)r.request();
		
		IOField f=container.getThisStruct().getFields().byName("r").orElseThrow();
		assertEquals(f.instanceToString(null, container, false).orElse(null), "A");
	}
	
	@Test
	void enumSerialization() throws NoSuchMethodException{
		ToolUtils.simulateMissingClasses(
			List.of(n->List.of(RandomEnum.class.getName(), EnumContainer.class.getName()).contains(n)),
			GeneralTypeHandlingTests.class.getMethod("make"),
			GeneralTypeHandlingTests.class.getMethod("use", byte[].class)
		);
	}
	
	public interface Partial extends IOInstance.Def<Partial>{
		int a();
		int b();
	}
	
	@Test(expectedExceptions=UnsupportedOperationException.class)
	void partialImpl(){
		Class<Partial> partialImpl=IOInstance.Def.partialImplementation(Partial.class, Set.of("a"));
		
		Partial partial=IOInstance.Def.of(partialImpl);
		
		partial.b();
	}
	
	@IOInstance.Def.Order({"name", "data"})
	public interface NamedBlob extends IOInstance.Def<NamedBlob>{
		String name();
		@IOCompression(RLE)
		byte[] data();
	}
	
	@Test
	void compressByteArray() throws IOException{
		TestUtils.testCluster(TestInfo.of(), provider->{
			var blob=IOInstance.Def.of(
				NamedBlob.class, "Hello world",
				"""
					aaaaaaaaaayyyyyyyyyyyyyyyyyy lmaooooooooooooooooooooo
					""".getBytes(UTF_8)
			);
			provider.getRootProvider().provide(new ObjectID("obj"), blob);
			var read=provider.getRootProvider().request("obj", NamedBlob.class);

//			assertTrue("Compression not working", chunk.chainSize()<64);
			
			assertEquals(blob, read);
		});
	}
	
	@DataProvider(name="templateTypes")
	Object[][] templateTypes(){
		return new Object[][]{
			{Partial.class, null},
			{NamedBlob.class, new Object[]{"aaa", new byte[10]}},
			};
	}
	
	@Test(dataProvider="templateTypes")
	<T extends IOInstance.Def<T>> void newTemplate(Class<T> type, Object[] args) throws IOException{
		if(args==null){
			IOInstance.Def.of(type);
		}else{
			IOInstance.Def.of(type, args);
		}
	}
	
	public enum E1b{
		E0
	}
	
	public enum E2b{
		E00,
		E01,
		E11
	}
	
	public enum E3b{
		E000,
		E001,
		E010,
		E011,
		E100,
		E101,
		E110,
		E111,
	}
	
	public enum E4b{
		E0000,
		E0001,
		E0010,
		E0011,
		E0100,
		E0101,
		E0110,
		E0111,
		E1000,
		E1001,
		E1010,
		E1011,
		E1100,
		E1101,
		E1110,
		E1111,
	}
	
	public interface E1 extends IOInstance.Def<E1>{
		List<E1b> nums();
	}
	
	public interface E2 extends IOInstance.Def<E2>{
		List<E2b> nums();
	}
	
	public interface E3 extends IOInstance.Def<E3>{
		List<E3b> nums();
	}
	
	public interface E4 extends IOInstance.Def<E4>{
		List<E4b> nums();
	}
	
	@DataProvider(name="enumHolders")
	Object[][] enumHolders(){
		return new Object[][]{
			{E1.class, E1b.class},
			{E2.class, E2b.class},
			{E3.class, E3b.class},
			{E4.class, E4b.class},
			};
	}
	
	@Test(dataProvider="enumHolders")
	<T extends IOInstance.Def<T>, E extends Enum<E>> void enumIntegrity(Class<T> type, Class<E> eType){
		var r   =new Random(69420);
		var info=EnumUniverse.of(eType);
		var pip =StandardStructPipe.of(type);
		var data=com.lapissea.cfs.chunk.DataProvider.newVerySimpleProvider();
		for(int i=0;i<1000;i++){
			
			var set=IOInstance.Def.of(
				type,
				r.ints(r.nextLong(i+1)+1, 0, info.size())
				 .mapToObj(info::get)
				 .toList()
			);
			
			try(var io=data.getSource().io()){
				io.setPos(0);
				pip.write(data, io, set);
				io.setPos(0);
				var read=pip.readNew(data, io, null);
				assertEquals("Failed equality on "+i, set, read);
			}catch(IOException e){
				throw new RuntimeException(i+"", e);
			}
		}
		
		
	}
	
}
