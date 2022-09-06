package com.lapissea.cfs.run;

import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.FixedContiguousStructPipe;
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
			var pipe=ContiguousStructPipe.of(GenericContainer.class);
			
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
			pipe=ContiguousStructPipe.of(struct, STATE_DONE);
		}catch(MalformedStructLayout|StagedInit.WaitException ignored){
			pipe=null;
		}
		if(pipe!=null){
			pipe.checkTypeIntegrity(struct.make());
		}
		try{
			pipe=FixedContiguousStructPipe.of(struct, STATE_DONE);
		}catch(MalformedStructLayout|StagedInit.WaitException ignored){
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
		Cluster.init(data).getRootProvider().request(EnumContainer.class, "hello!");
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
			provider.getRootProvider().provide(blob, new ObjectID("obj"));
			var read=provider.getRootProvider().request(NamedBlob.class, "obj");

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
	
}
