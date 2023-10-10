# JDatablockFS

### Description:

A java library that aims to store arbitrary data in a format that is compact, easily modifiable and flexible.
Aimed for storing many blocks of data efficiently while bypassing shortcomings of traditional
file systems or be used to store complex data structures such as game save files with minimum effort.

**Info for nerds:**

This library has at its core 2 tasks.

- Mapping of a blob of data (a File or byte[]) in to what is essentially a set of resizable byte[]
- Serialization (and deserialization) of arbitrary objects in to a strongly typed format well suited to be stored in small blob(s) of data

---

### Who is this for?

If you need to store data but do not want to deal with complicated SQL servers or are annoyed with writing queries, then this is a perfect alternative.

This library acts like a database, but it writes like regular objects/collections and there is no server at all. Everything can be simply stored in a single efficient self-contained file.

You don't need to write SQL files to set up tables. You just write a class with values that you wish to store. I'll do
the rest for you.

You don't need to deal with huge libraries like JPA. Objects are integrated in to the library!

If you do need to store a LOT of data, then a more traditional database is recommended.
This is no SQL killer. This is just a "I want it dummy simple" database.

---

### (Super simple) Example(s):

### IPSet example:

This shows very simple list of ipv6 and their cordinates on earth. (made up data)

_You can find the complete code of the example in `/jdatablockfs.run/src/main/java/com/lapissea/cfs/run/examples/IPs.java`_

```java
//Setting up classes
public static class IP extends IOInstance.Managed<IP>{
	@IOValue
	double latitude
	@IOValue
	double longitude;
	
	@IOValue
	String v6;
	
	//Every IOInstance needs an empty constructor
	public IP(){ }
	public IP(double latitude, double longitude, String v6){
		this.latitude = latitude;
		this.longitude = longitude;
		this.v6 = v6;
	}
}
```

Create database and add sample data:

```java
//init and create new Cluster with in memory (byte[]) data
Cluster cluster=Cluster.init(MemoryData.empty());

//Ask root provider for list of IPs with the id of "my ips"
IOList<IP> ips=cluster.roots().request("my ips",IOList.class,IP.class);

//Adding sample data to database:
ips.add(new IP(0.2213415,0.71346,"2001:0db8:85a3:0000:0000:8a2e:0370:7334"));
ips.add(new IP(0.6234,0.51341123,"2001:0db8:0:1:1:1:1:1"));
```

Details:

`cluster.roots().request` creates and initializes an object if it does not exist under the provided id within the database or returns an existing value. So referencing "my ips" in the future would return the same data that has been added in this example.

`ips` is an IOList (that defaults to an implementation called `ContiguousIOList`) and is automatically created and allocated by the rootProvider.

`ips.add` writes to the database immediately. This only happens with unmanaged instances. Setting a values on a regular instance changes no data on the actual database.

_Alternative IP type definition with an interface_

```java
@IOInstance.Def.Order({"latitude", "longitude", "v6"})
@IOInstance.Def.ToString.Format("{@v6 at @latitude / @longitude}")
public interface IP extends IOInstance.Def<IP>{
	
	static IP of(double latitude, double longitude, String v6){
		return IOInstance.Def.of(IP.class, latitude, longitude, v6);
	}
	
	double latitude();
	double longitude();
	String v6();
}
```

This is just an interface. It infers the fields from the contents of the interface. Every non static/default/private function is considered a getter/setter. Getters and setters can have multiple formats. (eg: `double getLatitude()` or `void setV6(String v6)`) A getter or setter is required for a field but not both of them.

- `IOInstance.Def` is the base type of template type (or definition type)
- `IOInstance.Def.Order` is an annotation that is required if the type needs a `void setAll(<all fields>)` or needs to be constructed with data. (aka `IOInstance.Def.of(<type>, <all field values in order>`)
- `IOInstance.Def.ToString.Format` or `IOInstance.Def.ToString` are optional and just give the ability to customize the field in a friendly way. Alternatively a `static String toString(<type> instance){...}` can be added to write a custom toString
- `static IP of(...)` is just a convenience function that is completely optional. It just makes it more pleasing to manually make an instance. It is a replacement for `new IP(...)`

Note that this creates implementation(s) as needed. This may provide a performance increase and clarity in a context where only partial access to data is needed. (such as finding an object by a spesific field) This is because manually creating a perfect instance is annoying and clumsy. Simply defining what fields you need offloads the annoying bolierplate work to the code generation mechanism inside the library.

NOTE: You *can* create your own implementation of an interface like this, but it is best for the internals of this library to do that for you.

---

### Maven:

Simply paste this in to your pom.xml

```xml
<dependencies>
	<dependency>
		<groupId>com.github.lapissea.jdatablockfs</groupId>
		<artifactId>core</artifactId>
		<version>1.0</version>
	</dependency>
</dependencies>
<repositories>
	<repository>
		<id>lapissnap</id>
		<url>https://raw.githubusercontent.com/LapisSea/maven-snaps/tree/master/repo/</url>
		<releases>
			<enabled>false</enabled>
		</releases>
		<snapshots>
			<enabled>true</enabled>
		</snapshots>
	</repository>
</repositories>
```

Or manually add the `https://raw.githubusercontent.com/LapisSea/maven-snaps/tree/master/repo/` reposetory and add an artifact with the group `com.github.lapissea.jdatablockfs` with the id `core` and the `1.0` version.

---

### Project structure:

| Location           | Description                                                                                                                                                                                                                                                                                                                                                                                      | Functionality_breakdown                                                                              |
|--------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------|
| jdatablockfs.core  | The core functionality of the library.<br/> This is the dependency to be linked in a production.<br/>Is very light weight. There is no big libraries attached. Danger of dependency conflicts or straight bloat is minimal.                                                                                                                                                                      | - Providing core interfaces<br/>- Memory management<br/>- Type engine<br/>- Serialization of data    |
| jdatablockfs.tools | This is where optional features and debugging tools are housed. Things like DisplayHost (a tool for visually viewing a file) is located.<br/>If there is a problem with a file or manual inspection of a file is needed then this is a dependency to be linked.<br/>This contains quite a bit of dependencies like gson, lwjgl and more.                                                         | - Visual debugging and inspection<br/>- IPC logging of memory events<br/>                            |
| jdatablockfs.run   | This is where examples, relatively realistic usage cases and tests are housed.<br/>This should not really be used as a dependency. If you want to run the examples, do so directly.                                                                                                                                                                                                              | - Providing examples<br/>- Sanity checking the design<br/>- exposing flaws while developing features |
| Jorth              | This is an independent library whose only job is to compile streams of text in to bytecode at runtime. This could be considered a custom language but should not be used anywhere outside the internals of this projects as the compiler is not tested or secured or considered a good language. If you need to generate bytecode at runtime, please use the ASM library directly or contact me. | - Generation of bytecode at runtime without manual ASM                                               |

---

### Note: Documentation is unfinished!!

**Please create an issue and ask a question about anything that is not clear! I'm just 1 guy, and it's hard to know what may be complicated or unclear when I've designed it. Thanks ☺️**

---

### Points of interest:

- `Cluster`: This acts like the root for a file. It takes an `IOInterface` and provides access to objects inside. Use `Cluster.init(...)` to create a new empty cluster on an IOInterface
- `IOInterface`: An interface that provides the lowest level interactions that are required for functioning of this library.
- `MemoryData`: An in memory implementation of IOInterface. Creation example: `MemoryData.builder().withRaw(new byte[]{1,2,3}).build()`
- `IOFileData`: An implementation of IOInterface. Maps to `java.io.File` (WARNING: This is unfinished, please use MemoryData for now)


- `IOInstance`: Class that is required to extend any object that needs to be stored. Fields and setters/getters inside it can be marked with `@IOValue` to be marked as things that should be stored
- `IOInstance.Unmanaged` Class that is like `IOInstance` except its contents may be unmanaged. Aka it has to manage the serialization, allocation of data and description of references and data types manually. A normal IOInstance is fully managed. Other parts of the library fully manage its data but in limited ways. Things like self referencing nodes, Lists, Maps, Sets or any more complicated data structure probably should be Unmanaged as such structures require more control than a base IOInstance can provide (and can do more efficient IO operations like reading a specific field). There are limitations to an unmanaged instance. Since it may contain code, it can not be serialized and its class has to be available in the classpath in order for a file to be (fully) readable. On the same note, since there is custom behaviour, the library can not make certain assumptions about the object like an exact size (`@IOValueUnmanaged` may help with this) or if copying or reallocation of an instance can be performed.


- `IOList`: A disk based `List`. It has implementations similar to `ArrayList` and `LinkedList`
- `IOMap`: A disk based `Map`. Its implementation is similar to `HashMap`


- `@IOValue`: This is the most basic annotation. It signifies that a value should be serialized.
- `@IONullability`: This annotation specified what kind of nullability is accepted. Every `IOValue` without this annotation is `NOT_NULL`.
	- When a field is `NOT_NULL` and is null, then the object it is in will be considered invalid and the library will refuse to store it.
	- When this annotation has `NULLABLE`, a new field of type boolean will be added to the instance under the hood. Its value represents if the relevant field is null or not. It is automatically managed by the library.
	- When this annotation has `DEFAULT_IF_NULL` then a value will be stored as its default value. (0, false, empty constructor, empty array, etc...)
- `@IOValue.Reference`: This annotation suggests to the library that this field should not be inlined and should be stored as a reference. (This is useful if an object is going to be stored in a list. When an object is in a list, the list greatly benefits from an object whose size can be fixed and can be stored inline with the list data)
- `@IOValue.OverrideType`: This annotation specifies that the type of field should be replaced with a compatible type. For example if there is an IOList and a specific implementation is desired then `@IOValue.OverrideType(FooList.class)` can be used
- `@IODependency`: This annotation specifies that this field requires another `IOField` to be read/written correctly. This will change the way the memory of an IOInstance is organised. If used incorrectly this annotation can create dependency loops what will make the instance of an invalid format. This annotation should probably not be used unless there is a specific need for it.
- `@IODependency.VirtualNumSize`: This annotation can be used on a field of a numeric type. It creates a virtual field of `NumberSize` what enables the number to be stored in less bytes than its max value requires. A name can be specified for the `NumberSize` field. If 2 fields share the same name, their `NumberSize` field will be shared.
- `@IODependency.NumSize`: This annotation has the same effect as `IODependency.VirtualNumSize` except it does not create a virtual field but references the name of another field.
- `@IOType.Dynamic`: This annotation enables the field to have a value whose class does not match the field exactly. This freedom comes at the cost of needing an extra typeID virtual field and any stored value will need to have its class layout stored on file what can create a lot of non-useful data. Use this annotation only when needed.
- `@IOValueUnmanaged`: This annotation can be used on static methods inside an `IOInstance.Unmanaged` to mark that function as a static unmanaged field factory.

---
