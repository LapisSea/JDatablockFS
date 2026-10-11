package com.lapissea.dfs.run;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.objects.collections.ContiguousIOList;
import com.lapissea.dfs.objects.collections.HashIOMap;
import com.lapissea.dfs.objects.collections.IOHashSet;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.type.string.StringifySettings;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.List;

import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.NULLABLE;
import static org.assertj.core.api.Assertions.assertThat;

public class StringifyTests{
	
	@IOValue
	public static class Point extends IOInstance.Managed<Point>{
		int x;
		int y;
		public Point(){ }
		public Point(int x, int y){ this.x = x; this.y = y; }
	}
	
	@IOValue
	public static class Shape extends IOInstance.Managed<Shape>{
		String      name;
		List<Point> points;
		public Shape(){ }
	}
	
	@IOValue
	public static class Flags extends IOInstance.Managed<Flags>{
		boolean active;
		boolean hidden;
		@IONullability(NULLABLE)
		String  note;
		public Flags(){ }
	}
	
	@IOValue
	public static class CustomToString extends IOInstance.Managed<CustomToString>{
		int val = 5;
		public CustomToString(){ }
		@Override
		public String toString(){ return "custom:" + val; }
	}
	
	@IOValue
	public static class CustomBoth extends IOInstance.Managed<CustomBoth>{
		int val = 5;
		public CustomBoth(){ }
		@Override
		public String toString(){ return "long:" + val; }
		@Override
		public String toShortString(){ return "short:" + val; }
	}
	
	private static Shape shape(){
		var s = new Shape();
		s.name = "tri";
		s.points = List.of(new Point(0, 0), new Point(1, 0), new Point(0, 1));
		return s;
	}
	
	@Test
	void compactUnchanged(){
		assertThat(new Point(1, 2).toString()).isEqualTo("Point{x=1, y=2}");
		assertThat(new Point(1, 2).toShortString()).isEqualTo("Point{x=1, y=2}");
	}
	
	@Test
	void prettyFitsOnOneLine(){
		assertThat(new Point(1, 2).toPrettyString(80)).isEqualTo("Point{x: 1, y: 2}");
	}
	
	@Test
	void prettyBreaksWhenNarrow(){
		assertThat(new Point(111111, 222222).toPrettyString(10))
			.isEqualTo("Point{\n\tx: 111111,\n\ty: 222222\n}");
	}
	
	@Test
	void nestedInstancesAndCollectionsFits(){
		assertThat(shape().toPrettyString(100))
			.isEqualTo("Shape{name: \"tri\", points: [{}, {x: 1}, {y: 1}]}");
	}
	
	@Test
	void listBreaksButElementsStayOnOneLine(){
		assertThat(shape().toPrettyString(40))
			.isEqualTo("Shape{\n\tname: \"tri\",\n\tpoints: [{}, {x: 1}, {y: 1}]\n}");
	}
	
	@Test
	void everythingBreaksWhenVeryNarrow(){
		assertThat(shape().toPrettyString(12))
			.isEqualTo("Shape{\n\tname: \"tri\",\n\tpoints: [\n\t\t{},\n\t\t{x: 1},\n\t\t{y: 1}\n\t]\n}");
	}
	
	@Test
	void worthlessValuesAreOmitted(){
		var f = new Flags();
		f.active = true;
		assertThat(f.toPrettyString(80)).isEqualTo("Flags{active}");
	}
	
	@Test
	void contiguousListOfInstances() throws IOException{
		ContiguousIOList<Point> list = Cluster.emptyMem().roots().request("points", ContiguousIOList.class, Point.class);
		list.addAll(List.of(new Point(1, 2), new Point(3, 4), new Point(5, 6)));
		
		assertThat(list.toPrettyString(120)).isEqualTo("[{x: 1, y: 2}, {x: 3, y: 4}, {x: 5, y: 6}]");
		assertThat(list.toPrettyString(20)).isEqualTo("[\n\t{x: 1, y: 2},\n\t{x: 3, y: 4},\n\t{x: 5, y: 6}\n]");
	}
	
	@Test
	void customToStringOnlyStaysConsistent(){
		var c = new CustomToString();
		
		assertThat(c.toString()).isEqualTo("custom:5");
		assertThat(c.toShortString()).isEqualTo("custom:5");
		assertThat(c.toPrettyString(80)).isEqualTo("custom:5");
	}
	
	@Test
	void customBothOverridesAreRespected(){
		var c = new CustomBoth();
		
		assertThat(c.toString()).isEqualTo("long:5");
		assertThat(c.toShortString()).isEqualTo("short:5");
		assertThat(c.toString(80)).isEqualTo("long:5");
	}
	
	@Test
	void repeatRunsAreCompressed() throws IOException{
		ContiguousIOList<Integer> list = Cluster.emptyMem().roots().request("ints", ContiguousIOList.class, Integer.class);
		list.addAll(List.of(1, 2));
		for(int i = 0; i<50; i++) list.add(0);
		list.add(3);
		
		assertThat(list.toPrettyString(200)).isEqualTo("[1, 2, 0 (50 times), 3]");
	}
	
	@Test
	void flatPrintIsCharacterBoundedButPrettyIsNot() throws IOException{
		ContiguousIOList<Integer> list = Cluster.emptyMem().roots().request("ints2", ContiguousIOList.class, Integer.class);
		for(int i = 0; i<200; i++) list.add(i);
		
		assertThat(list.toString()).contains("... ").hasSizeLessThan(400);
		assertThat(list.toPrettyString(100)).doesNotContain("... ");
	}
	
	@Test
	void flatCollectionLimitIsConfigurable() throws IOException{
		ContiguousIOList<Integer> list = Cluster.emptyMem().roots().request("ints3", ContiguousIOList.class, Integer.class);
		for(int i = 0; i<200; i++) list.add(i);
		
		var tiny = list.toString(StringifySettings.DEFAULT.withFlatCollectionLimit(10));
		assertThat(tiny).contains("... ").hasSizeLessThan(40);
	}
	
	@Test
	void repeatThresholdIsConfigurable() throws IOException{
		ContiguousIOList<Integer> list = Cluster.emptyMem().roots().request("ints4", ContiguousIOList.class, Integer.class);
		list.addAll(List.of(1, 1, 1));
		
		assertThat(list.toString()).doesNotContain("times)");
		assertThat(list.toString(StringifySettings.DEFAULT.withRepeatThreshold(2))).contains("1 (3 times)");
	}
	
	@Test
	void maxCollectionPrintIsConfigurable() throws IOException{
		ContiguousIOList<Integer> list = Cluster.emptyMem().roots().request("ints5", ContiguousIOList.class, Integer.class);
		for(int i = 0; i<50; i++) list.add(i);
		
		assertThat(list.toPrettyString(1000)).doesNotContain("... ");
		assertThat(list.toString(StringifySettings.PRETTY.withWidth(1000).withMaxCollectionPrint(10))).contains("... 40 more");
	}
	
	@Test
	void contiguousMapOfInstances() throws IOException{
		var map = Cluster.emptyMem().roots().<HashIOMap<Integer, Point>>request(1, HashIOMap.class, Integer.class, Point.class);
		map.put(7, new Point(1, 2));
		
		assertThat(map.toPrettyString(120)).isEqualTo("{7: {x: 1, y: 2}}");
		assertThat(map.toPrettyString(10)).isEqualTo("{\n\t7: {\n\t\tx: 1,\n\t\ty: 2\n\t}\n}");
	}
	
	@Test
	void contiguousMapStringsAreQuoted() throws IOException{
		var map = Cluster.emptyMem().roots().<HashIOMap<String, String>>request(1, HashIOMap.class, String.class, String.class);
		map.put("foo", "bar");
		
		assertThat(map.toPrettyString(120)).isEqualTo("{\"foo\": \"bar\"}");
	}
	
	@Test
	void contiguousSetOfInstances() throws IOException{
		var set = Cluster.emptyMem().roots().<IOHashSet<Point>>request(1, IOHashSet.class, Point.class);
		set.add(new Point(1, 2));
		
		assertThat(set.toPrettyString(120)).isEqualTo("{{x: 1, y: 2}}");
	}
}
