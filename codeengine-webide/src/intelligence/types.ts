/** JVM access flags, retained so the editor can distinguish instance/static API. */
export const Access = {
  PUBLIC: 0x0001,
  PRIVATE: 0x0002,
  PROTECTED: 0x0004,
  STATIC: 0x0008,
  FINAL: 0x0010,
  INTERFACE: 0x0200,
  ABSTRACT: 0x0400,
  SYNTHETIC: 0x1000,
  ANNOTATION: 0x2000,
  ENUM: 0x4000,
} as const;

export interface JavaMember {
  name: string;
  /** Unmodified JVM descriptor (for example `(Ljava/lang/String;)V`). */
  descriptor: string;
  signature?: string;
  access: number;
  deprecated: boolean;
  parameterNames?: string[];
  /** Long constants are decimal strings to preserve their exact value. */
  constantValue?: string | number;
}

export interface JavaClass {
  /** Binary class name: dot-separated packages; nested classes retain `$`. */
  name: string;
  superName?: string;
  interfaces: string[];
  access: number;
  signature?: string;
  deprecated: boolean;
  fields: JavaMember[];
  methods: JavaMember[];
}
