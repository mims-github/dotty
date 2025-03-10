package dotty.tools
package backend
package jvm


import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.transform.Erasure

/**
 * Core BTypes and some other definitions. The initialization of these definitions requies access
 * to symbols / types (global).
 *
 * The symbols used to initialize the ClassBTypes may change from one compiler run to the next. To
 * make sure the definitions are consistent with the symbols in the current run, the
 * `intializeCoreBTypes` method in BTypesFromSymbols creates a new instance of CoreBTypes in each
 * compiler run.
 *
 * The class BTypesFromSymbols does not directly reference CoreBTypes, but CoreBTypesProxy. The
 * reason is that having a `var bTypes: CoreBTypes` would not allow `import bTypes._`. Instead, the
 * proxy class holds a `CoreBTypes` in a variable field and forwards to this instance.
 *
 * The definitions in `CoreBTypes` need to be lazy vals to break an initialization cycle. When
 * creating a new instance to assign to the proxy, the `classBTypeFromSymbol` invoked in the
 * constructor will actucally go through the proxy. The lazy vals make sure the instance is assigned
 * in the proxy before the fields are initialized.
 *
 * Note: if we did not re-create the core BTypes on each compiler run, BType.classBTypeFromInternalNameMap
 * could not be a perRunCache anymore: the classes defeined here need to be in that map, they are
 * added when the ClassBTypes are created. The per run cache removes them, so they would be missing
 * in the second run.
 */
class CoreBTypes[BTFS <: BTypesFromSymbols[_ <: DottyBackendInterface]](val bTypes: BTFS) {
  import bTypes._
  import int.given
  import DottyBackendInterface._

  //import global._
  //import rootMirror.{requiredClass, getClassIfDefined}
  //import definitions._

  /**
   * Maps primitive types to their corresponding PrimitiveBType. The map is defined lexically above
   * the first use of `classBTypeFromSymbol` because that method looks at the map.
   */
  lazy val primitiveTypeMap: Map[Symbol, PrimitiveBType] = Map(
    defn.UnitClass    -> UNIT,
    defn.BooleanClass -> BOOL,
    defn.CharClass    -> CHAR,
    defn.ByteClass    -> BYTE,
    defn.ShortClass   -> SHORT,
    defn.IntClass     -> INT,
    defn.LongClass    -> LONG,
    defn.FloatClass   -> FLOAT,
    defn.DoubleClass  -> DOUBLE
  )

  lazy val BOXED_UNIT    : ClassBType = classBTypeFromSymbol(requiredClass[java.lang.Void])
  lazy val BOXED_BOOLEAN : ClassBType = classBTypeFromSymbol(requiredClass[java.lang.Boolean])
  lazy val BOXED_BYTE    : ClassBType = classBTypeFromSymbol(requiredClass[java.lang.Byte])
  lazy val BOXED_SHORT   : ClassBType = classBTypeFromSymbol(requiredClass[java.lang.Short])
  lazy val BOXED_CHAR    : ClassBType = classBTypeFromSymbol(requiredClass[java.lang.Character])
  lazy val BOXED_INT     : ClassBType = classBTypeFromSymbol(requiredClass[java.lang.Integer])
  lazy val BOXED_LONG    : ClassBType = classBTypeFromSymbol(requiredClass[java.lang.Long])
  lazy val BOXED_FLOAT   : ClassBType = classBTypeFromSymbol(requiredClass[java.lang.Float])
  lazy val BOXED_DOUBLE  : ClassBType = classBTypeFromSymbol(requiredClass[java.lang.Double])

  /**
   * Map from primitive types to their boxed class type. Useful when pushing class literals onto the
   * operand stack (ldc instruction taking a class literal), see genConstant.
   */
  lazy val boxedClassOfPrimitive: Map[PrimitiveBType, ClassBType] = Map(
    UNIT   -> BOXED_UNIT,
    BOOL   -> BOXED_BOOLEAN,
    BYTE   -> BOXED_BYTE,
    SHORT  -> BOXED_SHORT,
    CHAR   -> BOXED_CHAR,
    INT    -> BOXED_INT,
    LONG   -> BOXED_LONG,
    FLOAT  -> BOXED_FLOAT,
    DOUBLE -> BOXED_DOUBLE
  )

  lazy val boxedClasses: Set[ClassBType] = boxedClassOfPrimitive.values.toSet

  /**
   * Maps the method symbol for a box method to the boxed type of the result. For example, the
   * method symbol for `Byte.box()` is mapped to the ClassBType `java/lang/Byte`.
   */
  lazy val boxResultType: Map[Symbol, ClassBType] = {
    val boxMethods = defn.ScalaValueClasses().map{x => // @darkdimius Are you sure this should be a def?
      (x, Erasure.Boxing.boxMethod(x.asClass))
    }.toMap
    for ((valueClassSym, boxMethodSym) <- boxMethods)
    yield boxMethodSym -> boxedClassOfPrimitive(primitiveTypeMap(valueClassSym))
  }

  /**
   * Maps the method symbol for an unbox method to the primitive type of the result.
   * For example, the method symbol for `Byte.unbox()`) is mapped to the PrimitiveBType BYTE. */
  lazy val unboxResultType: Map[Symbol, PrimitiveBType] = {
    val unboxMethods: Map[Symbol, Symbol] =
      defn.ScalaValueClasses().map(x => (x, Erasure.Boxing.unboxMethod(x.asClass))).toMap
    for ((valueClassSym, unboxMethodSym) <- unboxMethods)
    yield unboxMethodSym -> primitiveTypeMap(valueClassSym)
  }

  /*
   * RT_NOTHING and RT_NULL exist at run-time only. They are the bytecode-level manifestation (in
   * method signatures only) of what shows up as NothingClass resp. NullClass in Scala ASTs.
   *
   * Therefore, when RT_NOTHING or RT_NULL are to be emitted, a mapping is needed: the internal
   * names of NothingClass and NullClass can't be emitted as-is.
   * TODO @lry Once there's a 2.11.3 starr, use the commented argument list. The current starr crashes on the type literal `scala.runtime.Nothing$`
   */
  lazy val RT_NOTHING : ClassBType = classBTypeFromSymbol(requiredClass("scala.runtime.Nothing$")) // (requiredClass[scala.runtime.Nothing$])
  lazy val RT_NULL    : ClassBType = classBTypeFromSymbol(requiredClass("scala.runtime.Null$"))    // (requiredClass[scala.runtime.Null$])

  lazy val ObjectReference   : ClassBType = classBTypeFromSymbol(defn.ObjectClass)
  lazy val objArrayReference : ArrayBType = ArrayBType(ObjectReference)

  lazy val StringRef                   : ClassBType = classBTypeFromSymbol(defn.StringClass)
  lazy val jlStringBuilderRef          : ClassBType = classBTypeFromSymbol(requiredClass[java.lang.StringBuilder])
  lazy val jlStringBufferRef           : ClassBType = classBTypeFromSymbol(requiredClass[java.lang.StringBuffer])
  lazy val jlCharSequenceRef           : ClassBType = classBTypeFromSymbol(requiredClass[java.lang.CharSequence])
  lazy val jlClassRef                  : ClassBType = classBTypeFromSymbol(requiredClass[java.lang.Class[_]])
  lazy val ThrowableReference          : ClassBType = classBTypeFromSymbol(defn.ThrowableClass)
  lazy val jlCloneableReference        : ClassBType = classBTypeFromSymbol(defn.JavaCloneableClass)        // java/lang/Cloneable
  lazy val jlNPEReference              : ClassBType = classBTypeFromSymbol(defn.NullPointerExceptionClass) // java/lang/NullPointerException
  lazy val jioSerializableReference    : ClassBType = classBTypeFromSymbol(requiredClass[java.io.Serializable])     // java/io/Serializable
  lazy val scalaSerializableReference  : ClassBType = classBTypeFromSymbol(requiredClass[scala.Serializable])         // scala/Serializable
  lazy val classCastExceptionReference : ClassBType = classBTypeFromSymbol(requiredClass[java.lang.ClassCastException])   // java/lang/ClassCastException
  lazy val jlIllegalArgExceptionRef: ClassBType = classBTypeFromSymbol(requiredClass[java.lang.IllegalArgumentException])
  lazy val jliSerializedLambdaRef: ClassBType = classBTypeFromSymbol(requiredClass[java.lang.invoke.SerializedLambda])

  lazy val srBooleanRef : ClassBType = classBTypeFromSymbol(requiredClass[scala.runtime.BooleanRef])
  lazy val srByteRef    : ClassBType = classBTypeFromSymbol(requiredClass[scala.runtime.ByteRef])
  lazy val srCharRef    : ClassBType = classBTypeFromSymbol(requiredClass[scala.runtime.CharRef])
  lazy val srIntRef     : ClassBType = classBTypeFromSymbol(requiredClass[scala.runtime.IntRef])
  lazy val srLongRef    : ClassBType = classBTypeFromSymbol(requiredClass[scala.runtime.LongRef])
  lazy val srFloatRef   : ClassBType = classBTypeFromSymbol(requiredClass[scala.runtime.FloatRef])
  lazy val srDoubleRef  : ClassBType = classBTypeFromSymbol(requiredClass[scala.runtime.DoubleRef])

  lazy val BoxesRunTime: ClassBType = classBTypeFromSymbol(requiredClass[scala.runtime.BoxesRunTime])

  /**
   * Methods in scala.runtime.BoxesRuntime
   */
  lazy val asmBoxTo  : Map[BType, MethodNameAndType] = Map(
    BOOL   -> MethodNameAndType("boxToBoolean",   MethodBType(List(BOOL),   BOXED_BOOLEAN)),
    BYTE   -> MethodNameAndType("boxToByte",      MethodBType(List(BYTE),   BOXED_BYTE)),
    CHAR   -> MethodNameAndType("boxToCharacter", MethodBType(List(CHAR),   BOXED_CHAR)),
    SHORT  -> MethodNameAndType("boxToShort",     MethodBType(List(SHORT),  BOXED_SHORT)),
    INT    -> MethodNameAndType("boxToInteger",   MethodBType(List(INT),    BOXED_INT)),
    LONG   -> MethodNameAndType("boxToLong",      MethodBType(List(LONG),   BOXED_LONG)),
    FLOAT  -> MethodNameAndType("boxToFloat",     MethodBType(List(FLOAT),  BOXED_FLOAT)),
    DOUBLE -> MethodNameAndType("boxToDouble",    MethodBType(List(DOUBLE), BOXED_DOUBLE))
  )

  lazy val asmUnboxTo: Map[BType, MethodNameAndType] = Map(
    BOOL   -> MethodNameAndType("unboxToBoolean", MethodBType(List(ObjectReference), BOOL)),
    BYTE   -> MethodNameAndType("unboxToByte",    MethodBType(List(ObjectReference), BYTE)),
    CHAR   -> MethodNameAndType("unboxToChar",    MethodBType(List(ObjectReference), CHAR)),
    SHORT  -> MethodNameAndType("unboxToShort",   MethodBType(List(ObjectReference), SHORT)),
    INT    -> MethodNameAndType("unboxToInt",     MethodBType(List(ObjectReference), INT)),
    LONG   -> MethodNameAndType("unboxToLong",    MethodBType(List(ObjectReference), LONG)),
    FLOAT  -> MethodNameAndType("unboxToFloat",   MethodBType(List(ObjectReference), FLOAT)),
    DOUBLE -> MethodNameAndType("unboxToDouble",  MethodBType(List(ObjectReference), DOUBLE))
  )

  lazy val typeOfArrayOp: Map[Int, BType] = {
    import dotty.tools.backend.ScalaPrimitivesOps._
    Map(
        (List(ZARRAY_LENGTH, ZARRAY_GET, ZARRAY_SET) map (_ -> BOOL))   ++
        (List(BARRAY_LENGTH, BARRAY_GET, BARRAY_SET) map (_ -> BYTE))   ++
        (List(SARRAY_LENGTH, SARRAY_GET, SARRAY_SET) map (_ -> SHORT))  ++
        (List(CARRAY_LENGTH, CARRAY_GET, CARRAY_SET) map (_ -> CHAR))   ++
        (List(IARRAY_LENGTH, IARRAY_GET, IARRAY_SET) map (_ -> INT))    ++
        (List(LARRAY_LENGTH, LARRAY_GET, LARRAY_SET) map (_ -> LONG))   ++
        (List(FARRAY_LENGTH, FARRAY_GET, FARRAY_SET) map (_ -> FLOAT))  ++
        (List(DARRAY_LENGTH, DARRAY_GET, DARRAY_SET) map (_ -> DOUBLE)) ++
        (List(OARRAY_LENGTH, OARRAY_GET, OARRAY_SET) map (_ -> ObjectReference)) : _*
    )
  }
}

/**
 * This trait make some core BTypes availalbe that don't depend on a Global instance. Some core
 * BTypes are required to be accessible in the BTypes trait, which does not have access to Global.
 *
 * BTypes cannot refer to CoreBTypesProxy because some of its members depend on global, for example
 * the type Symbol in
 *   def primitiveTypeMap: Map[Symbol, PrimitiveBType]
 */
trait CoreBTypesProxyGlobalIndependent[BTS <: BTypes] {
  val bTypes: BTS
  import bTypes._

  def boxedClasses: Set[ClassBType]

  def RT_NOTHING : ClassBType
  def RT_NULL    : ClassBType

  def ObjectReference          : ClassBType
  def jlCloneableReference     : ClassBType
  def jioSerializableReference : ClassBType
}

/**
 * See comment in class [[CoreBTypes]].
 */
final class CoreBTypesProxy[BTFS <: BTypesFromSymbols[_ <: DottyBackendInterface]](val bTypes: BTFS) extends CoreBTypesProxyGlobalIndependent[BTFS] {
  import bTypes._

  private var _coreBTypes: CoreBTypes[bTypes.type] = _
  def setBTypes(coreBTypes: CoreBTypes[BTFS]): Unit = {
    _coreBTypes = coreBTypes.asInstanceOf[CoreBTypes[bTypes.type]]
  }

  def primitiveTypeMap: Map[Symbol, PrimitiveBType] = _coreBTypes.primitiveTypeMap

  def BOXED_UNIT    : ClassBType = _coreBTypes.BOXED_UNIT
  def BOXED_BOOLEAN : ClassBType = _coreBTypes.BOXED_BOOLEAN
  def BOXED_BYTE    : ClassBType = _coreBTypes.BOXED_BYTE
  def BOXED_SHORT   : ClassBType = _coreBTypes.BOXED_SHORT
  def BOXED_CHAR    : ClassBType = _coreBTypes.BOXED_CHAR
  def BOXED_INT     : ClassBType = _coreBTypes.BOXED_INT
  def BOXED_LONG    : ClassBType = _coreBTypes.BOXED_LONG
  def BOXED_FLOAT   : ClassBType = _coreBTypes.BOXED_FLOAT
  def BOXED_DOUBLE  : ClassBType = _coreBTypes.BOXED_DOUBLE

  def boxedClasses: Set[ClassBType] = _coreBTypes.boxedClasses

  def boxedClassOfPrimitive: Map[PrimitiveBType, ClassBType] = _coreBTypes.boxedClassOfPrimitive

  def boxResultType: Map[Symbol, ClassBType] = _coreBTypes.boxResultType

  def unboxResultType: Map[Symbol, PrimitiveBType] = _coreBTypes.unboxResultType

  def RT_NOTHING : ClassBType = _coreBTypes.RT_NOTHING
  def RT_NULL    : ClassBType = _coreBTypes.RT_NULL

  def ObjectReference   : ClassBType = _coreBTypes.ObjectReference
  def objArrayReference : ArrayBType = _coreBTypes.objArrayReference

  def StringRef                   : ClassBType = _coreBTypes.StringRef
  def jlStringBuilderRef          : ClassBType = _coreBTypes.jlStringBuilderRef
  def jlStringBufferRef           : ClassBType = _coreBTypes.jlStringBufferRef
  def jlCharSequenceRef           : ClassBType = _coreBTypes.jlCharSequenceRef
  def jlClassRef                  : ClassBType = _coreBTypes.jlClassRef
  def ThrowableReference          : ClassBType = _coreBTypes.ThrowableReference
  def jlCloneableReference        : ClassBType = _coreBTypes.jlCloneableReference
  def jlNPEReference              : ClassBType = _coreBTypes.jlNPEReference
  def jioSerializableReference    : ClassBType = _coreBTypes.jioSerializableReference
  def scalaSerializableReference  : ClassBType = _coreBTypes.scalaSerializableReference
  def classCastExceptionReference : ClassBType = _coreBTypes.classCastExceptionReference
  def jlIllegalArgExceptionRef    : ClassBType = _coreBTypes.jlIllegalArgExceptionRef
  def jliSerializedLambdaRef      : ClassBType = _coreBTypes.jliSerializedLambdaRef

  def srBooleanRef : ClassBType = _coreBTypes.srBooleanRef
  def srByteRef    : ClassBType = _coreBTypes.srByteRef
  def srCharRef    : ClassBType = _coreBTypes.srCharRef
  def srIntRef     : ClassBType = _coreBTypes.srIntRef
  def srLongRef    : ClassBType = _coreBTypes.srLongRef
  def srFloatRef   : ClassBType = _coreBTypes.srFloatRef
  def srDoubleRef  : ClassBType = _coreBTypes.srDoubleRef

  def BoxesRunTime: ClassBType = _coreBTypes.BoxesRunTime

  def asmBoxTo  : Map[BType, MethodNameAndType] = _coreBTypes.asmBoxTo
  def asmUnboxTo: Map[BType, MethodNameAndType] = _coreBTypes.asmUnboxTo

  def typeOfArrayOp: Map[Int, BType] = _coreBTypes.typeOfArrayOp
}
