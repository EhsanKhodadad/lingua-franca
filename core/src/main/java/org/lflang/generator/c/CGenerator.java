/*************
 * Copyright (c) 2019-2021, The University of California at Berkeley.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
 * THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF
 * THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ***************/

package org.lflang.generator.c;

import static org.lflang.ast.ASTUtils.allActions;
import static org.lflang.ast.ASTUtils.allPorts;
import static org.lflang.ast.ASTUtils.allReactions;
import static org.lflang.ast.ASTUtils.allStateVars;
import static org.lflang.ast.ASTUtils.getInferredType;
import static org.lflang.ast.ASTUtils.isInitialized;
import static org.lflang.ast.ASTUtils.toDefinition;
import static org.lflang.ast.ASTUtils.toText;
import static org.lflang.util.StringUtil.addDoubleQuotes;

import com.google.common.base.Objects;
import com.google.common.collect.Iterables;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.xtext.xbase.lib.Exceptions;
import org.lflang.FileConfig;
import org.lflang.ast.ASTUtils;
import org.lflang.ast.DelayedConnectionTransformation;
import org.lflang.federated.extensions.CExtensionUtils;
import org.lflang.generator.ActionInstance;
import org.lflang.generator.CodeBuilder;
import org.lflang.generator.DelayBodyGenerator;
import org.lflang.generator.GeneratorBase;
import org.lflang.generator.GeneratorResult;
import org.lflang.generator.GeneratorUtils;
import org.lflang.generator.LFGeneratorContext;
import org.lflang.generator.ParameterInstance;
import org.lflang.generator.PortInstance;
import org.lflang.generator.ReactionInstance;
import org.lflang.generator.ReactorInstance;
import org.lflang.generator.TargetTypes;
import org.lflang.generator.TimerInstance;
import org.lflang.generator.TriggerInstance;
import org.lflang.generator.docker.CDockerGenerator;
import org.lflang.generator.docker.DockerGenerator;
import org.lflang.generator.python.PythonGenerator;
import org.lflang.lf.Action;
import org.lflang.lf.ActionOrigin;
import org.lflang.lf.Input;
import org.lflang.lf.Instantiation;
import org.lflang.lf.Mode;
import org.lflang.lf.Model;
import org.lflang.lf.Port;
import org.lflang.lf.Preamble;
import org.lflang.lf.Reaction;
import org.lflang.lf.Reactor;
import org.lflang.lf.ReactorDecl;
import org.lflang.lf.StateVar;
import org.lflang.lf.VarRef;
import org.lflang.lf.Variable;
import org.lflang.lf.WidthSpec;
import org.lflang.target.Target;
import org.lflang.target.TargetConfig;
import org.lflang.target.property.BuildCommandsProperty;
import org.lflang.target.property.CmakeIncludeProperty;
import org.lflang.target.property.CmakeInitIncludeProperty;
import org.lflang.target.property.CompileDefinitionsProperty;
import org.lflang.target.property.DockerProperty;
import org.lflang.target.property.FedSetupProperty;
import org.lflang.target.property.LoggingProperty;
import org.lflang.target.property.NoCompileProperty;
import org.lflang.target.property.NoSourceMappingProperty;
import org.lflang.target.property.PlatformProperty;
import org.lflang.target.property.PlatformProperty.PlatformOption;
import org.lflang.target.property.ProtobufsProperty;
import org.lflang.target.property.SchedulerProperty;
import org.lflang.target.property.SingleThreadedProperty;
import org.lflang.target.property.TracingProperty;
import org.lflang.target.property.WorkersProperty;
import org.lflang.target.property.type.PlatformType.Platform;
import org.lflang.util.ArduinoUtil;
import org.lflang.util.FileUtil;
import org.lflang.util.FlexPRETUtil;

/**
 * Generator for C target. This class generates C code defining each reactor class given in the
 * input .lf file and imported .lf files. The generated code has the following components:
 *
 * <ul>
 *   <li>A typedef for inputs, outputs, and actions of each reactor class. These define the types of
 *       the variables that reactions use to access inputs and action values and to set output
 *       values.
 *   <li>A typedef for a &quot;self&quot; struct for each reactor class. One instance of this struct
 *       will be created for each reactor instance. See below for details.
 *   <li>A function definition for each reaction in each reactor class. These functions take an
 *       instance of the self struct as an argument.
 *   <li>A constructor function for each reactor class. This is used to create a new instance of the
 *       reactor. After these, the main generated function is <code>_lf_initialize_trigger_objects()
 *       </code>. This function creates the instances of reactors (using their constructors) and
 *       makes connections between them. A few other smaller functions are also generated.
 *       <h2 id="self-struct">Self Struct</h2>
 *       The &quot;self&quot; struct has fields for each of the following:
 *   <li>parameter: the field name and type match the parameter.
 *   <li>state: the field name and type match the state.
 *   <li>action: the field name prepends the action name with &quot;<em>lf</em>&quot;. A second
 *       field for the action is also created to house the trigger_t object. That second field
 *       prepends the action name with &quot;_lf__&quot;.
 *   <li>output: the field name prepends the output name with &quot;<em>lf</em>&quot;.
 *   <li>input: the field name prepends the output name with &quot;<em>lf</em>&quot;. A second field
 *       for the input is also created to house the trigger_t object. That second field prepends the
 *       input name with &quot;_lf__&quot;. If, in addition, the reactor contains other reactors and
 *       reacts to their outputs, then there will be a struct within the self struct for each such
 *       contained reactor. The name of that self struct will be the name of the contained reactor
 *       prepended with &quot;<em>lf</em>&quot;. That inside struct will contain pointers the
 *       outputs of the contained reactors that are read together with pointers to booleans
 *       indicating whether those outputs are present. If, in addition, the reactor has a reaction
 *       to shutdown, then there will be a pointer to trigger_t object (see reactor.h) for the
 *       shutdown event and an action struct named _lf_shutdown on the self struct.
 *       <h2 id="reaction-functions">Reaction Functions</h2>
 *       For each reaction in a reactor class, this generator will produce a C function that expects
 *       a pointer to an instance of the &quot;self&quot; struct as an argument. This function will
 *       contain verbatim the C code specified in the reaction, but before that C code, the
 *       generator inserts a few lines of code that extract from the self struct the variables that
 *       that code has declared it will use. For example, if the reaction declares that it is
 *       triggered by or uses an input named &quot;x&quot; of type int, the function will contain a
 *       line like this:
 *       <pre><code>  r_x_t* x = <span class="hljs-keyword">self</span>-&gt;_lf_x;
 * </code></pre>
 *       where <code>r</code> is the full name of the reactor class and the struct type <code>r_x_t
 *       </code> has fields <code>is_present</code> and <code>value</code>, where the type of <code>
 *       value</code> matches the port type. If the programmer fails to declare that it uses x, then
 *       the absence of the above code will trigger a compile error when the verbatim code attempts
 *       to read <code>x</code>.
 *       <h2 id="constructor">Constructor</h2>
 *       For each reactor class, this generator will create a constructor function named <code>new_r
 *       </code>, where <code>r</code> is the reactor class name. This function will malloc and
 *       return a pointer to an instance of the &quot;self&quot; struct. This struct initially
 *       represents an unconnected reactor. To establish connections between reactors, additional
 *       information needs to be inserted (see below). The self struct is made visible to the body
 *       of a reaction as a variable named &quot;self&quot;. The self struct contains the following:
 *   <li>Parameters: For each parameter <code>p</code> of the reactor, there will be a field <code>p
 *       </code> with the type and value of the parameter. So C code in the body of a reaction can
 *       access parameter values as <code>self-&gt;p</code>.
 *   <li>State variables: For each state variable <code>s</code> of the reactor, there will be a
 *       field <code>s</code> with the type and value of the state variable. So C code in the body
 *       of a reaction can access state variables as <code>self-&gt;s</code>. The self struct also
 *       contains various fields that the user is not intended to use. The names of these fields
 *       begin with at least two underscores. They are:
 *   <li>Outputs: For each output named <code>out</code>, there will be a field <code>_lf_out</code>
 *       that is a struct containing a value field whose type matches that of the output. The output
 *       value is stored here. That struct also has a field <code>is_present</code> that is a
 *       boolean indicating whether the output has been set. This field is reset to false at the
 *       start of every time step. There is also a field <code>num_destinations</code> whose value
 *       matches the number of downstream reactors that use this variable. This field must be set
 *       when connections are made or changed. It is used to determine for a mutable input
 *       destination whether a copy needs to be made.
 *   <li>Inputs: For each input named <code>in</code> of type T, there is a field named <code>_lf_in
 *       </code> that is a pointer struct with a value field of type T. The struct pointed to also
 *       has an <code>is_present</code> field of type bool that indicates whether the input is
 *       present.
 *   <li>Outputs of contained reactors: If a reactor reacts to outputs of a contained reactor <code>
 *       r</code>, then the self struct will contain a nested struct named <code>_lf_r</code> that
 *       has fields pointing to those outputs. For example, if <code>r</code> has an output <code>
 *       out</code> of type T, then there will be field in <code>_lf_r</code> named <code>out</code>
 *       that points to a struct containing a value field of type T and a field named <code>
 *       is_present</code> of type bool.
 *   <li>Inputs of contained reactors: If a reactor sends to inputs of a contained reactor <code>r
 *       </code>, then the self struct will contain a nested struct named <code>_lf_r</code> that
 *       has fields for storing the values provided to those inputs. For example, if R has an input
 *       <code>in</code> of type T, then there will be field in _lf_R named <code>in</code> that is
 *       a struct with a value field of type T and a field named <code>is_present</code> of type
 *       bool.
 *   <li>Actions: If the reactor has an action a (logical or physical), then there will be a field
 *       in the self struct named <code>_lf_a</code> and another named <code>_lf__a</code>. The type
 *       of the first is specific to the action and contains a <code>value</code> field with the
 *       type and value of the action (if it has a value). That struct also has a <code>has_value
 *       </code> field, a <code>source_id</code> field, </code>an <code>is_present</code> field, and
 *       a <code>token</code> field (which is NULL if the action carries no value). The <code>_lf__a
 *       </code> field is of type trigger_t. That struct contains various things, including an array
 *       of reactions sensitive to this trigger and a lf_token_t struct containing the value of the
 *       action, if it has a value. See reactor.h in the C library for details.
 *   <li>Reactions: Each reaction will have several fields in the self struct. Each of these has a
 *       name that begins with <code>_lf__reaction_i</code>, where i is the number of the reaction,
 *       starting with 0. The fields are:
 *       <ul>
 *         <li>_lf__reaction_i: The struct that is put onto the reaction queue to execute the
 *             reaction (see reactor.h in the C library).
 *         <li>Timers: For each timer t, there is are two fields in the self struct:
 *             <ul>
 *               <li>_lf__t: The trigger_t struct for this timer (see reactor.h).
 *               <li>_lf__t_reactions: An array of reactions (pointers to the reaction_t structs on
 *                   this self struct) sensitive to this timer.
 *             </ul>
 *       </ul>
 *   <li>Triggers: For each Timer, Action, Input, and Output of a contained reactor that triggers
 *       reactions, there will be a trigger_t struct on the self struct with name <code>_lf__t
 *       </code>, where t is the name of the trigger.
 *       <h2 id="connections-between-reactors">Connections Between Reactors</h2>
 *       Establishing connections between reactors involves two steps. First, each destination (e.g.
 *       an input port) must have pointers to the source (the output port). As explained above, for
 *       an input named <code>in</code>, the field <code>_lf_in-&gt;value</code> is a pointer to the
 *       output data being read. In addition, <code>_lf_in-&gt;is_present</code> is a pointer to the
 *       corresponding <code>out-&gt;is_present</code> field of the output reactor&#39;s self
 *       struct. In addition, the <code>reaction_i</code> struct on the self struct has a <code>
 *       triggers</code> field that records all the trigger_t structs for ports and actions that are
 *       triggered by the i-th reaction. The triggers field is an array of arrays of pointers to
 *       trigger_t structs. The length of the outer array is the number of output channels (single
 *       ports plus multiport widths) that the reaction effects plus the number of input port
 *       channels of contained reactors that it effects. Each inner array has a length equal to the
 *       number of final destinations of that output channel or input channel. The reaction_i struct
 *       has an array triggered_sizes that indicates the sizes of these inner arrays. The
 *       num_outputs field of the reaction_i struct gives the length of the triggered_sizes and
 *       (outer) triggers arrays. The num_outputs field is equal to the total number of single ports
 *       and multiport channels that the reaction writes to.
 *       <h2 id="runtime-tables">Runtime Tables</h2>
 *       This generator creates an populates the following tables used at run time. These tables may
 *       have to be resized and adjusted when mutations occur.
 *   <li>is_present_fields: An array of pointers to booleans indicating whether an event is present.
 *       The _lf_start_time_step() function in reactor_common.c uses this to mark every event absent
 *       at the start of a time step. The size of this table is contained in the variable
 *       is_present_fields_size.
 *       <ul>
 *         <li>This table is accompanied by another list, is_present_fields_abbreviated, which only
 *             contains the is_present fields that have been set to true in the current tag. This
 *             list can allow a performance improvement if most ports are seldom present because
 *             only fields that have been set to true need to be reset to false.
 *       </ul>
 *   <li>_lf_shutdown_triggers: An array of pointers to trigger_t structs for shutdown reactions.
 *       The length of this table is in the _lf_shutdown_triggers_size variable.
 *   <li>timer_triggers: An array of pointers to trigger_t structs for timers that need to be
 *       started when the program runs. The length of this table is in the timer_triggers_size
 *       variable.
 *   <li>_lf_action_table: For a federated execution, each federate will have this table that maps
 *       port IDs to the corresponding action struct, which can be cast to action_base_t.
 * </ul>
 *
 * @author Edward A. Lee
 * @author Marten Lohstroh
 * @author Mehrdad Niknami
 * @author Christian Menard
 * @author Matt Weber
 * @author Soroush Bateni
 * @author Alexander Schulz-Rosengarten
 * @author Hou Seng Wong
 * @author Anirudh Rengarajan
 */
@SuppressWarnings("StaticPseudoFunctionalStyleMethod")
public class CGenerator extends GeneratorBase {

  // Regular expression pattern for compiler error messages with resource
  // and line number information. The first match will a resource URI in the
  // form of "file:/path/file.lf". The second match will be a line number.
  // The third match is a character position within the line.
  // The fourth match will be the error message.
  static final Pattern compileErrorPattern =
      Pattern.compile("^(?<path>.*):(?<line>\\d+):(?<column>\\d+):(?<message>.*)$");

  public static int UNDEFINED_MIN_SPACING = -1;

  ////////////////////////////////////////////
  //// Protected fields

  /** The main place to put generated code. */
  protected CodeBuilder code = new CodeBuilder();

  /** Place to collect code to initialize the trigger objects for all reactor instances. */
  protected CodeBuilder initializeTriggerObjects = new CodeBuilder();

  protected final CFileConfig fileConfig;

  /**
   * Count of the number of is_present fields of the self struct that need to be reinitialized in
   * _lf_start_time_step().
   */

  ////////////////////////////////////////////
  //// Private fields
  /** Extra lines that need to go into the generated CMakeLists.txt. */
  private final String cMakeExtras = "";

  /** Place to collect code to execute at the start of a time step. */
  private final CodeBuilder startTimeStep = new CodeBuilder();

  /**
   * Count of the number of token pointers that need to have their reference count decremented in
   * _lf_start_time_step().
   */
  private int shutdownReactionCount = 0;

  private int resetReactionCount = 0;
  private int watchdogCount = 0;

  // Indicate whether the generator is in Cpp mode or not
  private final boolean cppMode;

  private final CTypes types;

  protected CCmakeGenerator cmakeGenerator;

  protected CGenerator(
      LFGeneratorContext context,
      boolean cppMode,
      CTypes types,
      CCmakeGenerator cmakeGenerator,
      DelayBodyGenerator delayConnectionBodyGenerator) {
    super(context);
    this.fileConfig = (CFileConfig) context.getFileConfig();
    this.cppMode = cppMode;
    this.types = types;
    this.cmakeGenerator = cmakeGenerator;

    registerTransformation(
        new DelayedConnectionTransformation(
            delayConnectionBodyGenerator, types, fileConfig.resource, true, true));
  }

  public CGenerator(LFGeneratorContext context, boolean ccppMode) {
    this(
        context,
        ccppMode,
        new CTypes(),
        new CCmakeGenerator(context.getFileConfig(), List.of()),
        new CDelayBodyGenerator(new CTypes()));
  }

  /**
   * Look for physical actions in all resources. If found, set threads to be at least one to allow
   * asynchronous schedule calls.
   */
  public void accommodatePhysicalActionsIfPresent() {
    // If there are any physical actions, ensure the threaded engine is used and that
    // keepalive is set to true, unless the user has explicitly set it to false.
    for (Resource resource : GeneratorUtils.getResources(reactors)) {
      for (Action action : ASTUtils.allElementsOfClass(resource, Action.class)) {
        if (ActionOrigin.PHYSICAL.equals(action.getOrigin())) {
          // If the unthreaded runtime is not requested by the user, use the threaded runtime
          // instead
          // because it is the only one currently capable of handling asynchronous events.
          var singleThreaded = SingleThreadedProperty.INSTANCE;
          if (!targetConfig.isSet(singleThreaded) && targetConfig.get(singleThreaded)) {
            singleThreaded.override(targetConfig, true);
            String message =
                "Using the threaded C runtime to allow for asynchronous handling of physical action"
                    + " "
                    + action.getName();
            messageReporter.at(action).warning(message);
            return;
          }
        }
      }
    }
  }

  /**
   * Return true if the host operating system is compatible and otherwise report an error and return
   * false.
   */
  protected boolean isOSCompatible() {
    if (GeneratorUtils.isHostWindows()) {
      if (cppMode) {
        messageReporter
            .nowhere()
            .error(
                "LF programs with a CCpp target are currently not supported on Windows. "
                    + "Exiting code generation.");
        return false;
      }
    }
    return true;
  }

  /**
   * Generate C code from the Lingua Franca model contained by the specified resource. This is the
   * main entry point for code generation.
   *
   * @param resource The resource containing the source code.
   * @param context The context in which the generator is invoked, including whether it is cancelled
   *     and whether it is a standalone context
   */
  @Override
  public void doGenerate(Resource resource, LFGeneratorContext context) {
    super.doGenerate(resource, context);

    if (!GeneratorUtils.canGenerate(errorsOccurred(), mainDef, messageReporter, context)) return;
    if (!isOSCompatible()) return; // Incompatible OS and configuration

    // Perform set up that does not generate code
    if (!setUpGeneralParameters()) {
      // Failure.
      return;
    }

    FileUtil.createDirectoryIfDoesNotExist(fileConfig.getSrcGenPath().toFile());
    FileUtil.createDirectoryIfDoesNotExist(fileConfig.binPath.toFile());
    FileUtil.createDirectoryIfDoesNotExist(fileConfig.getIncludePath().toFile());
    handleProtoFiles();

    // Derive target filename from the .lf filename.
    var lfModuleName = fileConfig.name;
    var cFilename = CCompiler.getTargetFileName(lfModuleName, this.cppMode, targetConfig);
    var targetFile = fileConfig.getSrcGenPath() + File.separator + cFilename;
    try {
      generateCodeFor(lfModuleName);
      copyTargetFiles();
      generateHeaders();
      code.writeToFile(targetFile);
    } catch (IOException e) {
      String message = e.getMessage();
      messageReporter.nowhere().error(message);
    } catch (RuntimeException e) {
      String message = e.getMessage();
      messageReporter.nowhere().error(message);
      throw e;
    }

    // Inform the runtime of the number of watchdogs
    // TODO: Can we do this at a better place? We need to do it when we have the main reactor
    // since we need main to get all enclaves.
    var nWatchdogs =
        CUtil.getEnclaves(main).stream()
            .map(it -> it.enclaveInfo.numWatchdogs)
            .reduce(0, Integer::sum);
    CompileDefinitionsProperty.INSTANCE.update(
        targetConfig, Map.of("NUMBER_OF_WATCHDOGS", String.valueOf(nWatchdogs)));

    var isArduino =
        targetConfig.getOrDefault(PlatformProperty.INSTANCE).platform() == Platform.ARDUINO;
    var isFlexPRET =
        targetConfig.getOrDefault(PlatformProperty.INSTANCE).platform() == Platform.FLEXPRET;

    // If cmake is requested, generate the CMakeLists.txt
    if (!isArduino) {
      var cmakeFile = fileConfig.getSrcGenPath() + File.separator + "CMakeLists.txt";
      var sources =
          allTypeParameterizedReactors()
              .map(CUtil::getName)
              .map(it -> it + (cppMode ? ".cpp" : ".c"))
              .collect(Collectors.toCollection(ArrayList::new));
      sources.add(cFilename);
      var cmakeCode =
          cmakeGenerator.generateCMakeCode(sources, cppMode, mainDef != null, cMakeExtras, context);
      try {
        cmakeCode.writeToFile(cmakeFile);
      } catch (IOException e) {
        //noinspection ThrowableNotThrown,ResultOfMethodCallIgnored
        Exceptions.sneakyThrow(e);
      }
    } else {
      try {
        Path include = fileConfig.getSrcGenPath().resolve("include/");
        Path src = fileConfig.getSrcGenPath().resolve("src/");
        FileUtil.arduinoDeleteHelper(
            fileConfig.getSrcGenPath(), !targetConfig.get(SingleThreadedProperty.INSTANCE));
        FileUtil.relativeIncludeHelper(src, include, messageReporter);
        FileUtil.relativeIncludeHelper(include, include, messageReporter);
      } catch (IOException e) {
        //noinspection ThrowableNotThrown,ResultOfMethodCallIgnored
        Exceptions.sneakyThrow(e);
      }
      if (!targetConfig.get(NoCompileProperty.INSTANCE)) {
        ArduinoUtil arduinoUtil = new ArduinoUtil(context, commandFactory, messageReporter);
        arduinoUtil.buildArduino(fileConfig, targetConfig);
        context.finish(GeneratorResult.Status.COMPILED, null);
      } else {
        messageReporter.nowhere().info("********");
        messageReporter
            .nowhere()
            .info(
                "To compile your program, run the following command to see information about the"
                    + " board you plugged in:\n\n"
                    + "\tarduino-cli board list\n\n"
                    + "Grab the FQBN and PORT from the command and run the following command in the"
                    + " generated sources directory:\n\n"
                    + "\tarduino-cli compile -b <FQBN> --build-property"
                    + " compiler.c.extra_flags='-DLF_SINGLE_THREADED -DPLATFORM_ARDUINO"
                    + " -DINITIAL_EVENT_QUEUE_SIZE=10 -DINITIAL_REACT_QUEUE_SIZE=10'"
                    + " --build-property compiler.cpp.extra_flags='-DLF_SINGLE_THREADED"
                    + " -DPLATFORM_ARDUINO -DINITIAL_EVENT_QUEUE_SIZE=10"
                    + " -DINITIAL_REACT_QUEUE_SIZE=10' .\n\n"
                    + "To flash/upload your generated sketch to the board, run the following"
                    + " command in the generated sources directory:\n\n"
                    + "\tarduino-cli upload -b <FQBN> -p <PORT>\n");
        // System.out.println("For a list of all boards installed on your computer, you can use the
        // following command:\n\n\tarduino-cli board listall\n");
        context.finish(GeneratorResult.GENERATED_NO_EXECUTABLE.apply(context, null));
      }
      GeneratorUtils.refreshProject(resource, context.getMode());
      return;
    }

    var customBuildCommands = targetConfig.get(BuildCommandsProperty.INSTANCE);
    var dockerBuild = targetConfig.get(DockerProperty.INSTANCE);

    if (targetConfig.get(NoCompileProperty.INSTANCE)) {
      context.finish(GeneratorResult.GENERATED_NO_EXECUTABLE.apply(context, null));
    } else if (context.getMode() != LFGeneratorContext.Mode.LSP_MEDIUM) {
      if (customBuildCommands != null && !customBuildCommands.isEmpty()) {
        CUtil.runBuildCommand(
            fileConfig,
            targetConfig,
            commandFactory,
            messageReporter,
            this::reportCommandErrors,
            context.getMode());
        context.finish(GeneratorResult.Status.COMPILED, null);
      } else if (dockerBuild.enabled()) {
        if (targetConfig.target != Target.Python) {
          boolean success = buildUsingDocker();
          if (!success) {
            context.unsuccessfulFinish();
          }
        }
      } else {
        var cleanCode = code.removeLines("#line");
        var cCompiler = new CCompiler(targetConfig, fileConfig, messageReporter, cppMode);
        var success = false;
        try {
          success = cCompiler.runCCompiler(this, context);
        } catch (IOException e) {
          messageReporter.nowhere().error("Unexpected error during compilation.");
        } finally {
          if (!success) {
            // If compilation failed, remove any bin files that may have been created.
            messageReporter.nowhere().error("Compilation was unsuccessful.");
            CUtil.deleteBinFiles(fileConfig);
            context.unsuccessfulFinish();
          } else {
            try {
              cleanCode.writeToFile(targetFile);
            } catch (IOException e) {
              messageReporter
                  .nowhere()
                  .warning("Generated code may still contain line directives.");
            }
            context.finish(GeneratorResult.Status.COMPILED, null);
          }
        }
        if (isFlexPRET) {
          var platform = targetConfig.getOrDefault(PlatformProperty.INSTANCE);
          if (platform.flash().value()) {
            /**
             * Flash will result in two widely different responses when board is set to `emulator`
             * and `fpga`. For emulator, it will immediately run the emulator. For fpga, it will
             * attempt to transfer the program to the fpga.
             *
             * <p>It is FlexPRET's software development kit that handles all this; we just run the
             * script it generates.
             */
            FlexPRETUtil flexPRETUtil = new FlexPRETUtil(context, commandFactory, messageReporter);
            flexPRETUtil.flashTarget(fileConfig, targetConfig);
          }
        }
      }
    }

    // In case we are in Eclipse, make sure the generated code is visible.
    GeneratorUtils.refreshProject(resource, context.getMode());
  }

  private void generateCodeFor(String lfModuleName) throws IOException {
    code.pr(generateDirectives());
    code.pr(new CMainFunctionGenerator(targetConfig).generateCode());
    // Generate code for each reactor.
    generateReactorDefinitions();
    copyUserFiles(targetConfig, fileConfig);

    // Generate main instance, if there is one.
    // Note that any main reactors in imported files are ignored.
    // Skip generation if there are cycles.
    if (main != null) {
      var envFuncGen = new CEnvironmentFunctionGenerator(main, targetConfig, lfModuleName);

      code.pr(envFuncGen.generateDeclarations());
      initializeTriggerObjects.pr(
          String.join(
              "\n",
              "int bank_index;",
              "SUPPRESS_UNUSED_WARNING(bank_index);",
              "int watchdog_number = 0;",
              "SUPPRESS_UNUSED_WARNING(watchdog_number);"));
      // Add counters for modal initialization

      // Create an array of arrays to store all self structs.
      // This is needed because connections cannot be established until
      // all reactor instances have self structs because ports that
      // receive data reference the self structs of the originating
      // reactors, which are arbitarily far away in the program graph.
      generateSelfStructs(main);
      generateReactorInstance(main);

      code.pr(envFuncGen.generateDefinitions());

      if (targetConfig.isSet(FedSetupProperty.INSTANCE)) {
        if (targetLanguageIsCpp()) code.pr("extern \"C\" {");
        code.pr("#include \"" + targetConfig.get(FedSetupProperty.INSTANCE) + "\"");
        if (targetLanguageIsCpp()) code.pr("}");
      }

      // Generate function to initialize the trigger objects for all reactors.
      code.pr(
          CTriggerObjectsGenerator.generateInitializeTriggerObjects(
              main, targetConfig, initializeTriggerObjects, startTimeStep, types, lfModuleName));

      // Generate a function that will either do nothing
      // (if there is only one federate or the coordination
      // is set to decentralized) or, if there are
      // downstream federates, will notify the RTI
      // that the specified logical time is complete.
      if (!(this instanceof PythonGenerator)
          && (cppMode
              || targetConfig.get(PlatformProperty.INSTANCE).platform() == Platform.ARDUINO))
        code.pr("extern \"C\"");
      code.pr(
          String.join(
              "\n",
              "void logical_tag_complete(tag_t tag_to_send) {",
              CExtensionUtils.surroundWithIfElseFederatedCentralized(
                  "    lf_latest_tag_confirmed(tag_to_send);", "    (void) tag_to_send;"),
              "}"));

      // Generate an empty termination function for non-federated
      // execution. For federated execution, an implementation is
      // provided in federate.c.  That implementation will resign
      // from the federation and close any open sockets.
      code.pr(
          """
          #ifndef FEDERATED
          void lf_terminate_execution(environment_t* env) {
              (void) env;
          }
          #endif""");
    }
  }

  @Override
  public void checkModalReactorSupport(boolean __) {
    // Modal reactors are currently only supported for non federated applications
    super.checkModalReactorSupport(true);
  }

  @Override
  protected String getConflictingConnectionsInModalReactorsBody(VarRef sourceRef, VarRef destRef) {
    Instantiation sourceContainer = sourceRef.getContainer();
    Instantiation destContainer = destRef.getContainer();
    Port sourceAsPort = (Port) sourceRef.getVariable();
    Port destAsPort = (Port) destRef.getVariable();
    WidthSpec sourceWidth = sourceAsPort.getWidthSpec();
    WidthSpec destWidth = destAsPort.getWidthSpec();

    // NOTE: Have to be careful with naming count variables because if the name matches
    // that of a port, the program will fail to compile.

    // If the source or dest is a port of a bank, we need to iterate over it.
    var isBank = false;
    Instantiation bank = null;
    var sourceContainerRef = "";
    if (sourceContainer != null) {
      sourceContainerRef = sourceContainer.getName() + ".";
      bank = sourceContainer;
      if (bank.getWidthSpec() != null) {
        isBank = true;
        sourceContainerRef = sourceContainer.getName() + "[_lf_j].";
      }
    }
    var sourceIndex = isBank ? "_lf_i" : "_lf_c";
    var source =
        sourceContainerRef
            + sourceAsPort.getName()
            + ((sourceWidth != null) ? "[" + sourceIndex + "]" : "");
    var destContainerRef = "";
    var destIndex = "_lf_c";
    if (destContainer != null) {
      destIndex = "_lf_i";
      destContainerRef = destContainer.getName() + ".";
      if (bank == null) {
        bank = destContainer;
        if (bank.getWidthSpec() != null) {
          isBank = true;
          destContainerRef = destContainer.getName() + "[_lf_j].";
        }
      }
    }
    var dest =
        destContainerRef
            + destAsPort.getName()
            + ((destWidth != null) ? "[" + destIndex + "]" : "");
    var result = new StringBuilder();
    result.append("{ int _lf_c = 0; SUPPRESS_UNUSED_WARNING(_lf_c); ");
    // If either side is a bank (only one side should be), iterate over it.
    if (isBank) {
      var width = new StringBuilder();
      for (var term : bank.getWidthSpec().getTerms()) {
        if (!width.isEmpty()) width.append(" + ");
        if (term.getCode() != null) width.append(term.getCode().getBody());
        else if (term.getParameter() != null)
          width.append("self->" + term.getParameter().getName());
        else width.append(term.getWidth());
      }
      result.append("for(int _lf_j = 0; _lf_j < " + width.toString() + "; _lf_j++) { ");
    }
    // If either side is a multiport, iterate.
    // Note that one side could be a multiport of width 1 and the other an ordinary port.
    if (sourceWidth != null || destWidth != null) {
      var width =
          (sourceAsPort.getWidthSpec() != null)
              ? sourceContainerRef + sourceAsPort.getName()
              : destContainerRef + destAsPort.getName();
      result.append("for(int _lf_i = 0; _lf_i < " + width + "_width; _lf_i++) { ");
    }
    result.append("lf_set(" + dest + ", " + source + "->value); _lf_c++; ");
    if (sourceWidth != null || destAsPort.getWidthSpec() != null) {
      result.append(" }");
    }
    if (isBank) result.append(" }");
    result.append(" }");
    return result.toString();
  }

  /**
   * Copy all files or directories listed in the target property {@code files}, {@code
   * cmake-include}, and {@code _fed_setup} into the src-gen folder of the main .lf file
   *
   * @param targetConfig The targetConfig to read the target properties from.
   * @param fileConfig The fileConfig used to make the copy and resolve paths.
   */
  @Override
  protected void copyUserFiles(TargetConfig targetConfig, FileConfig fileConfig) {
    super.copyUserFiles(targetConfig, fileConfig);
    // Must use class variable to determine destination!
    var destination = this.fileConfig.getSrcGenPath();

    if (targetConfig.isSet(CmakeIncludeProperty.INSTANCE)) {
      FileUtil.copyFilesOrDirectories(
          targetConfig.get(CmakeIncludeProperty.INSTANCE),
          destination,
          fileConfig,
          messageReporter,
          true);
    }

    if (targetConfig.isSet(CmakeInitIncludeProperty.INSTANCE)) {
      FileUtil.copyFilesOrDirectories(
          targetConfig.get(CmakeInitIncludeProperty.INSTANCE),
          destination,
          fileConfig,
          messageReporter,
          true);
    }

    try {
      var file = targetConfig.get(FedSetupProperty.INSTANCE);
      if (file != null) {
        FileUtil.copyFile(fileConfig.srcFile.getParent().resolve(file), destination.resolve(file));
      }
    } catch (IOException e) {
      messageReporter
          .nowhere()
          .error("Failed to find _fed_setup file " + targetConfig.get(FedSetupProperty.INSTANCE));
    }
  }

  /**
   * Generate code for defining all instantiated reactors and collect preambles and relevant target
   * properties associated with imported reactors.
   */
  private void generateReactorDefinitions() throws IOException {
    if (this.main != null) {
      generateReactorChildren(this.main, new LinkedHashSet<>());
      generateReactorClass(new TypeParameterizedReactor(this.mainDef, reactors));
    }
    // do not generate code for reactors that are not instantiated
  }

  private record TypeParameterizedReactorWithDecl(TypeParameterizedReactor tpr, ReactorDecl decl) {
    @Override
    public boolean equals(Object obj) {
      // This is equivalence modulo decl
      return obj == this
          || obj instanceof TypeParameterizedReactorWithDecl tprd && tprd.tpr.equals(this.tpr);
    }

    @Override
    public int hashCode() {
      return tpr.hashCode();
    }
  }

  /** Generate user-visible header files for all reactors instantiated. */
  private void generateHeaders() throws IOException {
    FileUtil.deleteDirectory(fileConfig.getIncludePath());
    FileUtil.copyFromClassPath(
        fileConfig.getRuntimeIncludePath(), fileConfig.getIncludePath(), false, true);
    for (TypeParameterizedReactor tpr :
        (Iterable<TypeParameterizedReactor>) () -> allTypeParameterizedReactors().iterator()) {
      CReactorHeaderFileGenerator.doGenerate(
          types,
          tpr,
          fileConfig,
          (builder, rr, userFacing) -> {
            generateAuxiliaryStructs(builder, rr, userFacing);
            if (userFacing) {
              ASTUtils.allInstantiations(rr.reactor()).stream()
                  .map(
                      it ->
                          new TypeParameterizedReactorWithDecl(
                              new TypeParameterizedReactor(it, rr), it.getReactorClass()))
                  .distinct()
                  .forEach(
                      it ->
                          ASTUtils.allPorts(it.tpr.reactor())
                              .forEach(
                                  p ->
                                      builder.pr(
                                          CPortGenerator.generateAuxiliaryStruct(
                                              it.tpr,
                                              p,
                                              getTarget(),
                                              messageReporter,
                                              types,
                                              new CodeBuilder(),
                                              true,
                                              it.decl()))));
            }
          },
          this::generateTopLevelPreambles);
    }
    FileUtil.copyDirectoryContents(
        fileConfig.getIncludePath(), fileConfig.getSrcGenPath().resolve("include"), false);
  }

  /**
   * Recursively generate code for the children of the given reactor and collect preambles and
   * relevant target properties associated with imported reactors.
   *
   * @param reactor Used to extract children from
   */
  private void generateReactorChildren(
      ReactorInstance reactor, LinkedHashSet<TypeParameterizedReactor> generatedReactors)
      throws IOException {
    for (ReactorInstance r : reactor.children) {
      var newTpr = r.tpr;
      if (r.reactorDeclaration != null && !generatedReactors.contains(newTpr)) {
        generatedReactors.add(newTpr);
        generateReactorChildren(r, generatedReactors);
        generateReactorClass(newTpr);
      }
    }
  }

  /**
   * Choose which platform files to compile with according to the OS. If there is no main reactor,
   * then compilation will produce a .o file requiring further linking. Also, if useCmake is set to
   * true, we don't need to add platform files. The CMakeLists.txt file will detect and use the
   * appropriate platform file based on the platform that cmake is invoked on.
   */
  private void pickCompilePlatform() {
    var osName = System.getProperty("os.name").toLowerCase();
    // if platform target was set, use given platform instead
    if (targetConfig.get(PlatformProperty.INSTANCE).platform() != Platform.AUTO) {
      osName = targetConfig.get(PlatformProperty.INSTANCE).platform().toString();
    } else if (Stream.of("mac", "darwin", "win", "nux").noneMatch(osName::contains)) {
      messageReporter.nowhere().error("Platform " + osName + " is not supported");
    }
  }

  /** Copy target-specific header file to the src-gen directory. */
  protected void copyTargetFiles() throws IOException {
    Path dest = fileConfig.getSrcGenPath();
    var arduino = false;
    if (targetConfig.isSet(PlatformProperty.INSTANCE)) {
      var platform = targetConfig.get(PlatformProperty.INSTANCE).platform();
      switch (platform) {
        case ARDUINO -> {
          // For Arduino, alter the destination directory.
          dest = dest.resolve("src");
          arduino = true;
        }
        case ZEPHYR -> {
          // For the Zephyr target, copy default config and board files.
          FileUtil.copyFromClassPath(
              "/lib/platform/zephyr/boards", fileConfig.getSrcGenPath(), false, false);
          FileUtil.copyFileFromClassPath(
              "/lib/platform/zephyr/prj_lf.conf", fileConfig.getSrcGenPath(), true);

          FileUtil.copyFileFromClassPath(
              "/lib/platform/zephyr/Kconfig", fileConfig.getSrcGenPath(), true);
        }
        case RP2040 -> {
          // For the pico src-gen, copy over vscode configurations for debugging
          Path vscodePath = fileConfig.getSrcGenPath().resolve(".vscode");
          // If pico-sdk-path not defined, this can be used to pull the sdk into src-gen
          FileUtil.copyFileFromClassPath(
              "/lib/platform/rp2040/pico_sdk_import.cmake", fileConfig.getSrcGenPath(), true);
          // VS Code configurations
          FileUtil.copyFileFromClassPath("/lib/platform/rp2040/launch.json", vscodePath, true);
        }
      }
    }

    // Copy the core lib
    if (context.getArgs().externalRuntimeUri() != null) {
      Path coreLib = Paths.get(context.getArgs().externalRuntimeUri());
      FileUtil.copyDirectoryContents(coreLib, dest, true);
    } else {
      for (var directory : List.of("core", "lib")) {
        FileUtil.copyFromClassPath("/lib/c/reactor-c/" + directory, dest, true, false);
      }
      for (var directory :
          List.of("logging", "platform", "low_level_platform", "trace", "version", "tag")) {
        var entry = "/lib/c/reactor-c/" + directory;
        if (arduino) {
          if (FileConfig.class.getResource(entry + "/api") != null) {
            FileUtil.copyFromClassPath(
                entry + "/api",
                fileConfig.getSrcGenPath().resolve("include").resolve(directory),
                true,
                false);
          }
          if (FileConfig.class.getResource(entry + "/impl") != null) {
            FileUtil.copyFromClassPath(entry + "/impl", dest.resolve(directory), true, false);
          }
        } else {
          FileUtil.copyFromClassPath(entry, dest, true, false);
        }
      }
    }
  }

  ////////////////////////////////////////////
  //// Code generators.

  /**
   * Generate a reactor class definition for the specified federate. A class definition has four
   * parts:
   *
   * <ul>
   *   <li>Preamble code, if any, specified in the Lingua Franca file.
   *   <li>A &quot;self&quot; struct type definition (see the class documentation above).
   *   <li>A function for each reaction.
   *   <li>A constructor for creating an instance. for deleting an instance.
   * </ul>
   *
   * <p>If the reactor is the main reactor, then the generated code may be customized. Specifically,
   * if the main reactor has reactions, these reactions will not be generated if they are triggered
   * by or send data to contained reactors that are not in the federate.
   */
  private void generateReactorClass(TypeParameterizedReactor tpr) throws IOException {
    // FIXME: Currently we're not reusing definitions for declarations that point to the same
    // definition.
    CodeBuilder header = new CodeBuilder();
    CodeBuilder src = new CodeBuilder();
    final String headerName = CUtil.getName(tpr) + ".h";
    var guardMacro = CUtil.internalIncludeGuard(tpr);
    header.pr("#ifndef " + guardMacro);
    header.pr("#define " + guardMacro);
    generateReactorClassHeaders(tpr, headerName, header, src);
    header.pr(generateTopLevelPreambles(tpr.reactor()));
    generateUserPreamblesForReactor(tpr.reactor(), src);
    generateReactorClassBody(tpr, header, src);
    header.pr("#endif // " + guardMacro);
    header.writeToFile(fileConfig.getSrcGenPath().resolve(headerName).toString());
    var extension = CCompiler.getFileExtension(cppMode, targetConfig);
    src.writeToFile(fileConfig.getSrcGenPath().resolve(CUtil.getName(tpr) + extension).toString());
  }

  protected void generateReactorClassHeaders(
      TypeParameterizedReactor tpr, String headerName, CodeBuilder header, CodeBuilder src) {
    if (cppMode) {
      src.pr("extern \"C\" {");
      header.pr("extern \"C\" {");
    }
    header.pr("#include \"include/core/reactor.h\"");
    src.pr("#include \"include/api/schedule.h\"");
    src.pr(
        CExtensionUtils.surroundWithIfFederated(
            "#include <string.h>")); // For memcpy. Only needed for federated.
    if (CPreambleGenerator.arduinoBased(targetConfig)) {
      src.pr("#include \"include/low_level_platform/api/low_level_platform.h\"");
    } else {
      src.pr("#include \"low_level_platform/api/low_level_platform.h\"");
    }
    generateIncludes(tpr);
    if (cppMode) {
      src.pr("}");
      header.pr("}");
    }
    src.pr("#include \"include/" + CReactorHeaderFileGenerator.outputPath(tpr) + "\"");
    src.pr("#include \"" + headerName + "\"");
    tpr.doDefines(src);
    CUtil.allIncludes(tpr).stream().map(name -> "#include \"" + name + ".h\"").forEach(header::pr);
  }

  private void generateReactorClassBody(
      TypeParameterizedReactor tpr, CodeBuilder header, CodeBuilder src) {
    // Some of the following methods create lines of code that need to
    // go into the constructor.  Collect those lines of code here:
    var constructorCode = new CodeBuilder();
    var suppressLineDirectives = targetConfig.get(NoSourceMappingProperty.INSTANCE);
    generateAuxiliaryStructs(header, tpr, false);
    // The following must go before the self struct so the #include watchdog.h ends up in the
    // header.
    CWatchdogGenerator.generateWatchdogs(src, header, tpr, suppressLineDirectives, messageReporter);
    generateSelfStruct(header, tpr, constructorCode);
    generateMethods(src, tpr);
    generateReactions(src, tpr);
    generateConstructor(src, header, tpr, constructorCode);
  }

  /** Generate methods for {@code reactor}. */
  protected void generateMethods(CodeBuilder src, TypeParameterizedReactor tpr) {
    CMethodGenerator.generateMethods(
        tpr, src, types, this.targetConfig.get(NoSourceMappingProperty.INSTANCE));
  }

  /**
   * Generates preambles defined by user for a given reactor
   *
   * @param reactor The given reactor
   */
  protected void generateUserPreamblesForReactor(Reactor reactor, CodeBuilder src) {
    for (Preamble p : ASTUtils.allPreambles(reactor)) {
      src.pr("// *********** From the preamble, verbatim:");
      var suppressLineDirectives = this.targetConfig.get(NoSourceMappingProperty.INSTANCE);
      src.prSourceLineNumber(p.getCode(), suppressLineDirectives);
      src.pr(toText(p.getCode()));
      src.prEndSourceLineNumber(suppressLineDirectives);
      src.pr("\n// *********** End of preamble.");
    }
  }

  /**
   * Generate a constructor for the specified reactor in the specified federate.
   *
   * @param tpr The parsed reactor data structure.
   * @param constructorCode Lines of code previously generated that need to go into the constructor.
   */
  protected void generateConstructor(
      CodeBuilder src,
      CodeBuilder header,
      TypeParameterizedReactor tpr,
      CodeBuilder constructorCode) {
    header.pr(CConstructorGenerator.generateConstructorPrototype(tpr));
    src.pr(CConstructorGenerator.generateConstructor(tpr, constructorCode.toString()));
  }

  protected void generateIncludes(TypeParameterizedReactor tpr) {
    code.pr("#include \"" + CUtil.getName(tpr) + ".h\"");
  }

  /**
   * Generate the struct type definitions for inputs, outputs, and actions of the specified reactor.
   */
  protected void generateAuxiliaryStructs(
      CodeBuilder builder, TypeParameterizedReactor tpr, boolean userFacing) {
    // In the case where there are incoming
    // p2p logical connections in decentralized
    // federated execution, there will be an
    // intended_tag field added to accommodate
    // the case where a reaction triggered by a
    // port or action is late due to network
    // latency, etc..
    var federatedExtension = new CodeBuilder();
    federatedExtension.pr(
        String.format(
            """
            #ifdef FEDERATED
            #ifdef FEDERATED_DECENTRALIZED
            %s intended_tag;
            #endif
            %s physical_time_of_arrival;
            #endif
            """,
            types.getTargetTagType(), types.getTargetTimeType()));
    for (Port p : allPorts(tpr.reactor())) {
      builder.pr(
          CPortGenerator.generateAuxiliaryStruct(
              tpr, p, getTarget(), messageReporter, types, federatedExtension, userFacing, null));
    }
    // The very first item on this struct needs to be
    // a trigger_t* because the struct will be cast to (trigger_t*)
    // by the lf_schedule() functions to get to the trigger.
    for (Action action : allActions(tpr.reactor())) {
      builder.pr(
          CActionGenerator.generateAuxiliaryStruct(
              tpr, action, getTarget(), types, federatedExtension, userFacing));
    }
  }

  /**
   * Generate the self struct type definition for the specified reactor in the specified federate.
   *
   * @param constructorCode Place to put lines of code that need to go into the constructor.
   */
  private void generateSelfStruct(
      CodeBuilder builder, TypeParameterizedReactor tpr, CodeBuilder constructorCode) {
    var reactor = toDefinition(tpr.reactor());
    var selfType = CUtil.selfType(tpr);
    var suppressLineDirectives = this.targetConfig.get(NoSourceMappingProperty.INSTANCE);
    // Construct the typedef for the "self" struct.
    // Create a type name for the self struct.
    var body = new CodeBuilder();

    // Extensions can add functionality to the CGenerator
    generateSelfStructExtension(body, reactor, constructorCode);

    // Next handle parameters.
    body.pr(CParameterGenerator.generateDeclarations(tpr, types, suppressLineDirectives));

    // Next handle states.
    body.pr(CStateGenerator.generateDeclarations(tpr, types, suppressLineDirectives));

    // Next handle actions.
    CActionGenerator.generateDeclarations(tpr, body, constructorCode);

    // Next handle inputs and outputs.
    CPortGenerator.generateDeclarations(tpr, reactor, body, constructorCode);

    // If there are contained reactors that either receive inputs
    // from reactions of this reactor or produce outputs that trigger
    // reactions of this reactor, then we need to create a struct
    // inside the self struct for each contained reactor. That
    // struct has a place to hold the data produced by this reactor's
    // reactions and a place to put pointers to data produced by
    // the contained reactors.
    generateInteractingContainedReactors(tpr, body, constructorCode);

    // Next, generate the fields needed for each reaction.
    CReactionGenerator.generateReactionAndTriggerStructs(body, tpr, constructorCode, types);

    // Generate the fields needed for each watchdog.
    CWatchdogGenerator.generateWatchdogStruct(body, tpr, constructorCode);

    // Next, generate fields for modes
    CModesGenerator.generateDeclarations(reactor, body, constructorCode);

    // The first field has to always be a pointer to the list of
    // of allocated memory that must be freed when the reactor is freed.
    // This means that the struct can be safely cast to self_base_t.
    builder.pr("typedef struct {");
    builder.indent();
    builder.pr("struct self_base_t base;");
    builder.pr(body.toString());
    builder.unindent();
    builder.pr("} " + selfType + ";");
  }

  /**
   * Generate structs and associated code for contained reactors that send or receive data to or
   * from the container's reactions.
   *
   * <p>If there are contained reactors that either receive inputs from reactions of this reactor or
   * produce outputs that trigger reactions of this reactor, then we need to create a struct inside
   * the self struct of the container for each contained reactor. That struct has a place to hold
   * the data produced by the container reactor's reactions and a place to put pointers to data
   * produced by the contained reactors.
   *
   * @param tpr {@link TypeParameterizedReactor}
   * @param body The place to put the struct definition for the contained reactors.
   * @param constructorCode The place to put matching code that goes in the container's constructor.
   */
  private void generateInteractingContainedReactors(
      TypeParameterizedReactor tpr, CodeBuilder body, CodeBuilder constructorCode) {
    // The contents of the struct will be collected first so that
    // we avoid duplicate entries and then the struct will be constructed.
    var contained = new InteractingContainedReactors(tpr.reactor());
    // Next generate the relevant code.
    for (Instantiation containedReactor : contained.containedReactors()) {
      var containedTpr = new TypeParameterizedReactor(containedReactor, tpr);
      // First define an _width variable in case it is a bank.
      var array = "";
      var width = -2;
      // If the instantiation is a bank, find the maximum bank width
      // to define an array.
      if (containedReactor.getWidthSpec() != null) {
        width = CReactionGenerator.maxContainedReactorBankWidth(containedReactor, null, 0, mainDef);
        array = "[" + width + "]";
      }
      // NOTE: The following needs to be done for each instance
      // so that the width can be parameter, not in the constructor.
      // Here, we conservatively use a width that is the largest of all instances.
      constructorCode.pr(
          String.join(
              "\n",
              "// Set the _width variable for all cases. This will be -2",
              "// if the reactor is not a bank of reactors.",
              "self->_lf_" + containedReactor.getName() + "_width = " + width + ";"));

      // Generate one struct for each contained reactor that interacts.
      body.pr("struct {");
      body.indent();
      for (Port port : contained.portsOfInstance(containedReactor)) {
        if (port instanceof Input) {
          // If the variable is a multiport, then the place to store the data has
          // to be malloc'd at initialization.
          if (!ASTUtils.isMultiport(port)) {
            // Not a multiport.
            body.pr(variableStructType(port, containedTpr, false) + " " + port.getName() + ";");
          } else {
            // Is a multiport.
            // Memory will be malloc'd in initialization.
            body.pr(
                String.join(
                    "\n",
                    variableStructType(port, containedTpr, false) + "** " + port.getName() + ";",
                    "int " + port.getName() + "_width;"));
          }
        } else {
          // Must be an output port.
          // Outputs of contained reactors are pointers to the source of data on the
          // self struct of the container.
          if (!ASTUtils.isMultiport(port)) {
            // Not a multiport.
            body.pr(variableStructType(port, containedTpr, false) + "* " + port.getName() + ";");
          } else {
            // Is a multiport.
            // Here, we will use an array of pointers.
            // Memory will be malloc'd in initialization.
            body.pr(
                String.join(
                    "\n",
                    variableStructType(port, containedTpr, false) + "** " + port.getName() + ";",
                    "int " + port.getName() + "_width;"));
          }
          body.pr("trigger_t " + port.getName() + "_trigger;");
          var reactorIndex = "";
          if (containedReactor.getWidthSpec() != null) {
            reactorIndex = "[reactor_index]";
            constructorCode.pr(
                "for (int reactor_index = 0; reactor_index < self->_lf_"
                    + containedReactor.getName()
                    + "_width; reactor_index++) {");
            constructorCode.indent();
          }
          var portOnSelf =
              "self->_lf_" + containedReactor.getName() + reactorIndex + "." + port.getName();

          constructorCode.pr(
              CExtensionUtils.surroundWithIfFederatedDecentralized(
                  portOnSelf
                      + "_trigger.intended_tag = (tag_t) { .time = NEVER, .microstep = 0u};"));

          var triggered = contained.reactionsTriggered(containedReactor, port);
          //noinspection StatementWithEmptyBody
          if (triggered.size() > 0) {
            body.pr("reaction_t* " + port.getName() + "_reactions[" + triggered.size() + "];");
            var triggeredCount = 0;
            for (Integer index : triggered) {
              constructorCode.pr(
                  portOnSelf
                      + "_reactions["
                      + triggeredCount++
                      + "] = &self->_lf__reaction_"
                      + index
                      + ";");
            }
            constructorCode.pr(portOnSelf + "_trigger.reactions = " + portOnSelf + "_reactions;");
          } else {
            // Since the self struct is created using calloc, there is no need to set
            // self->_lf_"+containedReactor.getName()+"."+port.getName()+"_trigger.reactions = NULL
          }
          // Since the self struct is created using calloc, there is no need to set falsy fields.
          constructorCode.pr(
              String.join(
                  "\n",
                  portOnSelf + "_trigger.last_tag = NEVER_TAG;",
                  portOnSelf + "_trigger.number_of_reactions = " + triggered.size() + ";"));

          // Set the physical_time_of_arrival
          constructorCode.pr(
              CExtensionUtils.surroundWithIfFederated(
                  portOnSelf + "_trigger.physical_time_of_arrival = NEVER;"));

          if (containedReactor.getWidthSpec() != null) {
            constructorCode.unindent();
            constructorCode.pr("}");
          }
        }
      }
      body.unindent();
      body.pr(
          String.join(
              "\n",
              "} _lf_" + containedReactor.getName() + array + ";",
              "int _lf_" + containedReactor.getName() + "_width;"));
    }
  }

  /**
   * This function is provided to allow extensions of the CGenerator to append the structure of the
   * self struct
   *
   * @param body The body of the self struct
   * @param reactor The reactor declaration for the self struct
   * @param constructorCode Code that is executed when the reactor is instantiated
   */
  protected void generateSelfStructExtension(
      CodeBuilder body, Reactor reactor, CodeBuilder constructorCode) {
    // Do nothing
  }

  /**
   * Generate reaction functions definition for a reactor. These functions have a single argument
   * that is a void* pointing to a struct that contains parameters, state variables, inputs
   * (triggering or not), actions (triggering or produced), and outputs.
   *
   * @param tpr The reactor.
   */
  public void generateReactions(CodeBuilder src, TypeParameterizedReactor tpr) {
    var reactionIndex = 0;
    var reactor = ASTUtils.toDefinition(tpr.reactor());
    for (Reaction reaction : allReactions(reactor)) {
      generateReaction(src, reaction, tpr, reactionIndex);
      // Increment reaction index even if the reaction is not in the federate
      // so that across federates, the reaction indices are consistent.
      reactionIndex++;
    }
  }

  /**
   * Generate a reaction function definition for a reactor. This function will have a single
   * argument that is a void* pointing to a struct that contains parameters, state variables, inputs
   * (triggering or not), actions (triggering or produced), and outputs.
   *
   * @param reaction The reaction.
   * @param tpr The reactor.
   * @param reactionIndex The position of the reaction within the reactor.
   */
  protected void generateReaction(
      CodeBuilder src, Reaction reaction, TypeParameterizedReactor tpr, int reactionIndex) {
    src.pr(
        CReactionGenerator.generateReaction(
            reaction,
            tpr,
            reactionIndex,
            mainDef,
            messageReporter,
            types,
            targetConfig,
            getTarget().requiresTypes));
  }

  /**
   * Record startup, shutdown, and reset reactions.
   *
   * @param instance A reactor instance.
   */
  private void recordBuiltinTriggers(ReactorInstance instance) {
    // For each reaction instance, allocate the arrays that will be used to
    // trigger downstream reactions.

    var enclaveInfo = CUtil.getClosestEnclave(instance).enclaveInfo;
    var enclaveStruct = CUtil.getEnvironmentStruct(instance);
    var enclaveId = CUtil.getEnvironmentId(instance);
    for (ReactionInstance reaction : instance.reactions) {
      var reactor = reaction.getParent();
      var temp = new CodeBuilder();
      var foundOne = false;

      var reactionRef = CUtil.reactionRef(reaction);

      // Next handle triggers of the reaction that come from a multiport output
      // of a contained reactor.  Also, handle startup and shutdown triggers.
      for (TriggerInstance<?> trigger : reaction.triggers) {
        if (trigger.isStartup()) {
          temp.pr(
              enclaveStruct
                  + ".startup_reactions[startup_reaction_count["
                  + enclaveId
                  + "]++] = &"
                  + reactionRef
                  + ";");
          enclaveInfo.numStartupReactions += reactor.getTotalWidth();
          foundOne = true;
        } else if (trigger.isShutdown()) {
          temp.pr(
              enclaveStruct
                  + ".shutdown_reactions[shutdown_reaction_count["
                  + enclaveId
                  + "]++] = &"
                  + reactionRef
                  + ";");
          foundOne = true;
          enclaveInfo.numShutdownReactions += reactor.getTotalWidth();

          if (targetConfig.get(TracingProperty.INSTANCE).isEnabled()) {
            var description = CUtil.getShortenedName(reactor);
            var reactorRef = CUtil.reactorRef(reactor);
            temp.pr(
                String.join(
                    "\n",
                    "_lf_register_trace_event("
                        + reactorRef
                        + ", &("
                        + reactorRef
                        + "->_lf__shutdown),",
                    "trace_trigger, " + addDoubleQuotes(description + ".shutdown") + ");"));
          }
        } else if (trigger.isReset()) {
          temp.pr(
              enclaveStruct
                  + ".reset_reactions[reset_reaction_count["
                  + enclaveId
                  + "]++] = &"
                  + reactionRef
                  + ";");
          enclaveInfo.numResetReactions += reactor.getTotalWidth();
          foundOne = true;
        }
      }
      if (foundOne) initializeTriggerObjects.pr(temp.toString());
    }
  }

  /**
   * Generate code to set up the tables used in _lf_start_time_step to decrement reference counts
   * and mark outputs absent between time steps. This function puts the code into startTimeStep.
   */
  private void generateStartTimeStep(ReactorInstance instance) {
    // Avoid generating dead code if nothing is relevant.
    var foundOne = false;
    var temp = new CodeBuilder();
    var containerSelfStructName = CUtil.reactorRef(instance);
    var enclave = CUtil.getClosestEnclave(instance);
    var enclaveInfo = enclave.enclaveInfo;
    var enclaveStruct = CUtil.getEnvironmentStruct(enclave);

    // Handle inputs that get sent data from a reaction rather than from
    // another contained reactor and reactions that are triggered by an
    // output of a contained reactor.
    // Note that there may be more than one reaction reacting to the same
    // port so we have to avoid listing the port more than once.
    var portsSeen = new LinkedHashSet<PortInstance>();
    for (ReactionInstance reaction : instance.reactions) {
      for (PortInstance port : Iterables.filter(reaction.effects, PortInstance.class)) {
        if (port.getDefinition() instanceof Input && !portsSeen.contains(port)) {
          portsSeen.add(port);
          // This reaction is sending to an input. Must be
          // the input of a contained reactor in the federate.
          // NOTE: If instance == main and the federate is within a bank,
          // this assumes that the reaction writes only to the bank member in the federate.
          foundOne = true;

          temp.pr("// Add port " + port.getFullName() + " to array of is_present fields.");

          // We will be iterating over instance (if a bank) and the parent of the port (if a bank).
          var width = instance.getWidth() * port.getParent().getWidth();

          if (!Objects.equal(port.getParent(), instance)) {
            // The port belongs to contained reactor, so we also have
            // iterate over the instance bank members.
            temp.startScopedBlock();
            temp.pr("int count = 0; SUPPRESS_UNUSED_WARNING(count);");
            temp.startScopedBlock(instance);
            temp.startScopedBankChannelIteration(port, null);
          } else {
            // This branch should not occur because if the port's parent is instance and the port
            // is an effect of a reaction of instance, then the port must be an output, not an
            // input.
            // Nevertheless, leave this here in case we missed something.
            temp.startScopedBankChannelIteration(port, "count");
            width = port.getParent().getWidth();
          }
          var portRef = CUtil.portRefNested(port);
          var con = (port.isMultiport()) ? "->" : ".";

          var indexString =
              String.valueOf(enclaveInfo.numIsPresentFields)
                  + " + ("
                  + CUtil.runtimeIndex(instance.getParent())
                  + ") * "
                  + width * port.getWidth()
                  + " + count";

          temp.pr(
              enclaveStruct
                  + ".is_present_fields["
                  + indexString
                  + "] = &"
                  + portRef
                  + con
                  + "is_present;");
          // Intended_tag is only applicable to ports in federated execution.
          temp.pr(
              CExtensionUtils.surroundWithIfFederatedDecentralized(
                  enclaveStruct
                      + "._lf_intended_tag_fields["
                      + indexString
                      + "] = &"
                      + portRef
                      + con
                      + "intended_tag;"));

          enclaveInfo.numIsPresentFields += port.getParent().getTotalWidth() * port.getWidth();

          if (!Objects.equal(port.getParent(), instance)) {
            temp.pr("count++;");
            temp.endScopedBankChannelIteration(port, null);
            temp.endScopedBlock();
            temp.endScopedBlock();
          } else {
            temp.endScopedBankChannelIteration(port, "count");
          }
        }
      }
    }
    if (foundOne) startTimeStep.pr(temp.toString());
    temp = new CodeBuilder();
    foundOne = false;

    for (ActionInstance action : instance.actions) {
      foundOne = true;

      // Build the index into `is_present_fields` for this action.
      var indexString =
          String.valueOf(enclaveInfo.numIsPresentFields)
              + " + ("
              + CUtil.runtimeIndex(instance.getParent())
              + ") * "
              + action.getParent().getWidth();

      if (instance.isBank()) {
        indexString += " +  " + CUtil.bankIndexName(instance);
      }

      temp.pr(
          String.join(
              "\n",
              "// Add action " + action.getFullName() + " to array of is_present fields.",
              enclaveStruct + ".is_present_fields[" + indexString + "]",
              "        = (bool *) &"
                  + containerSelfStructName
                  + "->_lf__"
                  + action.getName()
                  + ".status;"));

      // Intended_tag is only applicable to actions in federated execution with decentralized
      // coordination.
      temp.pr(
          CExtensionUtils.surroundWithIfFederatedDecentralized(
              String.join(
                  "\n",
                  "// Add action " + action.getFullName() + " to array of intended_tag fields.",
                  enclaveStruct + "._lf_intended_tag_fields[" + indexString + "]",
                  "        = &"
                      + containerSelfStructName
                      + "->_lf_"
                      + action.getName()
                      + ".intended_tag;")));

      enclaveInfo.numIsPresentFields += action.getParent().getTotalWidth();
    }
    if (foundOne) startTimeStep.pr(temp.toString());
    temp = new CodeBuilder();
    foundOne = false;

    // Next, set up the table to mark each output of each contained reactor absent.
    for (ReactorInstance child : instance.children) {
      if (child.outputs.size() > 0) {

        temp.startScopedBlock();
        temp.pr("int count = 0; SUPPRESS_UNUSED_WARNING(count);");
        temp.startScopedBlock(child);

        // Need to find the total number of channels over all output ports of the child before
        // generating the
        // iteration.
        var totalChannelCount = 0;
        for (PortInstance output : child.outputs) {
          if (!output.getDependsOnReactions().isEmpty()) {
            totalChannelCount += output.getWidth();
          }
        }
        for (PortInstance output : child.outputs) {
          if (!output.getDependsOnReactions().isEmpty()) {
            foundOne = true;
            temp.pr(
                "// Add output port " + output.getFullName() + " to array of is_present fields.");
            temp.startChannelIteration(output);
            var indexString =
                "("
                    + CUtil.runtimeIndex(instance)
                    + ") * "
                    + totalChannelCount * child.getWidth()
                    + " + "
                    + enclaveInfo.numIsPresentFields
                    + " + count";

            temp.pr(
                enclaveStruct
                    + ".is_present_fields["
                    + indexString
                    + "] = &"
                    + CUtil.portRef(output)
                    + ".is_present;");

            // Intended_tag is only applicable to ports in federated execution with decentralized
            // coordination.
            temp.pr(
                CExtensionUtils.surroundWithIfFederatedDecentralized(
                    String.join(
                        "\n",
                        "// Add output port "
                            + output.getFullName()
                            + " to array of intended_tag fields.",
                        enclaveStruct
                            + "._lf_intended_tag_fields["
                            + indexString
                            + "] = &"
                            + CUtil.portRef(output)
                            + ".intended_tag;")));

            temp.pr("count++;");
            temp.endChannelIteration(output);
          }
        }
        temp.endScopedBlock();
        temp.endScopedBlock();
        enclaveInfo.numIsPresentFields += totalChannelCount * child.getTotalWidth();
      }
    }
    if (foundOne) startTimeStep.pr(temp.toString());
  }

  /**
   * For each timer in the given reactor, generate initialization code for the offset and period
   * fields.
   *
   * <p>This method will also populate the global timer_triggers array, which is used to start all
   * timers at the start of execution.
   *
   * @param instance A reactor instance.
   */
  private void generateTimerInitializations(ReactorInstance instance) {
    for (TimerInstance timer : instance.timers) {
      if (!timer.isStartup()) {
        initializeTriggerObjects.pr(CTimerGenerator.generateInitializer(timer));
        CUtil.getClosestEnclave(instance).enclaveInfo.numTimerTriggers +=
            timer.getParent().getTotalWidth();
      }
    }
  }

  /**
   * Process a given .proto file.
   *
   * <p>Run, if possible, the proto-c protocol buffer code generator to produce the required .h and
   * .c files.
   *
   * @param filename Name of the file to process.
   */
  public void processProtoFile(String filename) {
    var protoc =
        commandFactory.createCommand(
            "protoc-c",
            List.of("--c_out=" + this.fileConfig.getSrcGenPath(), filename),
            fileConfig.srcPath);
    if (protoc == null) {
      messageReporter.nowhere().error("Processing .proto files requires protoc-c >= 1.3.3.");
      return;
    }
    var returnCode = protoc.run();
    if (returnCode == 0) {
      var nameSansProto = filename.substring(0, filename.length() - 6);
      targetConfig.compileAdditionalSources.add(
          fileConfig.getSrcGenPath().resolve(nameSansProto + ".pb-c.c").toString());
    } else {
      messageReporter.nowhere().error("protoc-c returns error code " + returnCode);
    }
  }

  /**
   * Construct a unique type for the struct of the specified typed variable (port or action) of the
   * specified reactor class. This is required to be the same as the type name returned by {@link
   * #variableStructType(TriggerInstance)}.
   */
  public static String variableStructType(
      Variable variable, TypeParameterizedReactor tpr, boolean userFacing) {
    return (userFacing ? tpr.getName().toLowerCase() : CUtil.getName(tpr))
        + "_"
        + variable.getName()
        + "_t";
  }

  /**
   * Construct a unique type for the struct of the specified instance (port or action). This is
   * required to be the same as the type name returned by {@link #variableStructType(Variable,
   * TypeParameterizedReactor, boolean)}.
   *
   * @param portOrAction The port or action instance.
   * @return The name of the self struct.
   */
  public static String variableStructType(TriggerInstance<?> portOrAction) {
    return CUtil.getName(portOrAction.getParent().tpr) + "_" + portOrAction.getName() + "_t";
  }

  /**
   * If tracing is turned on, then generate code that records the full name of the specified reactor
   * instance in the trace table. If tracing is not turned on, do nothing.
   *
   * @param instance The reactor instance.
   */
  private void generateTraceTableEntries(ReactorInstance instance) {
    if (targetConfig.get(TracingProperty.INSTANCE).isEnabled()) {
      initializeTriggerObjects.pr(CTracingGenerator.generateTraceTableEntries(instance));
    }
  }

  /**
   * Generate code to instantiate the specified reactor instance and initialize it.
   *
   * @param instance A reactor instance.
   */
  public void generateReactorInstance(ReactorInstance instance) {
    var reactorClass = ASTUtils.toDefinition(instance.getDefinition().getReactorClass());
    var fullName = instance.getFullName();
    initializeTriggerObjects.pr(
        "// ***** Start initializing " + fullName + " of class " + reactorClass.getName());
    // Generate the instance self struct containing parameters, state variables,
    // and outputs (the "self" struct).
    initializeTriggerObjects.pr(
        CUtil.reactorRefName(instance)
            + "["
            + CUtil.runtimeIndex(instance)
            + "] = new_"
            + CUtil.getName(instance.tpr)
            + "();");
    initializeTriggerObjects.pr(
        CUtil.reactorRefName(instance)
            + "["
            + CUtil.runtimeIndex(instance)
            + "]->base.environment = &envs["
            + CUtil.getEnvironmentId(instance)
            + "];");
    // Generate code to initialize the "self" struct in the
    // _lf_initialize_trigger_objects function.
    generateTraceTableEntries(instance);
    generateReactorInstanceExtension(instance);
    generateParameterInitialization(instance);
    initializeOutputMultiports(instance);
    initializeInputMultiports(instance);
    recordBuiltinTriggers(instance);
    CWatchdogGenerator.generateInitializeWatchdogs(initializeTriggerObjects, instance);

    // Next, initialize the "self" struct with state variables.
    // These values may be expressions that refer to the parameter values defined above.
    generateStateVariableInitializations(instance);

    // Generate trigger objects for the instance.
    generateTimerInitializations(instance);
    generateActionInitializations(instance);
    generateInitializeActionToken(instance);
    generateSetDeadline(instance);
    generateModeStructure(instance);

    // Recursively generate code for the children.
    for (ReactorInstance child : instance.children) {
      // If this reactor is a placeholder for a bank of reactors, then generate
      // an array of instances of reactors and create an enclosing for loop.
      // Need to do this for each of the builders into which the code writes.
      startTimeStep.startScopedBlock(child);
      initializeTriggerObjects.startScopedBlock(child);
      // Generate the parent self struct for children to access its params
      initializeTriggerObjects.pr(
          CUtil.selfType(instance)
              + " *self = "
              + CUtil.reactorRefName(instance)
              + "["
              + CUtil.runtimeIndex(instance)
              + "];");
      generateReactorInstance(child);
      initializeTriggerObjects.endScopedBlock();
      startTimeStep.endScopedBlock();
    }

    // For this instance, define what must be done at the start of
    // each time step. This sets up the tables that are used by the
    // _lf_start_time_step() function in reactor_common.c.
    // Note that this function is also run once at the end
    // so that it can deallocate any memory.
    generateStartTimeStep(instance);
    initializeTriggerObjects.pr("//***** End initializing " + fullName);
  }

  /**
   * For each action of the specified reactor instance, generate initialization code for the offset
   * and period fields.
   *
   * @param instance The reactor.
   */
  private void generateActionInitializations(ReactorInstance instance) {
    initializeTriggerObjects.pr(CActionGenerator.generateInitializers(instance));
  }

  /**
   * Initialize actions by creating a lf_token_t in the self struct. This has the information
   * required to allocate memory for the action payload. Skip any action that is not actually used
   * as a trigger.
   *
   * @param reactor The reactor containing the actions.
   */
  private void generateInitializeActionToken(ReactorInstance reactor) {
    for (ActionInstance action : reactor.actions) {
      // Skip this step if the action is not in use.
      if (action.getParent().getTriggers().contains(action)) {
        var type = reactor.tpr.resolveType(getInferredType(action.getDefinition()));
        var payloadSize = "0";
        if (!type.isUndefined()) {
          var typeStr = types.getTargetType(type);
          if (CUtil.isTokenType(type)) {
            typeStr = CUtil.rootType(typeStr);
          }
          if (typeStr != null && !typeStr.equals("") && !typeStr.equals("void")) {
            payloadSize = "sizeof(" + typeStr + ")";
          }
        }

        var selfStruct = CUtil.reactorRef(action.getParent());
        initializeTriggerObjects.pr(
            CActionGenerator.generateTokenInitializer(selfStruct, action.getName(), payloadSize));
      }
    }
  }

  /**
   * Generate code that is executed while the reactor instance is being initialized. This is
   * provided as an extension point for subclasses. Normally, the reactions argument is the full
   * list of reactions, but for the top-level of a federate, will be a subset of reactions that is
   * relevant to the federate.
   *
   * @param instance The reactor instance.
   */
  protected void generateReactorInstanceExtension(ReactorInstance instance) {
    // Do nothing
  }

  /**
   * Generate code that initializes the state variables for a given instance. Unlike parameters,
   * state variables are uniformly initialized for all instances of the same reactor.
   *
   * @param instance The reactor class instance
   */
  protected void generateStateVariableInitializations(ReactorInstance instance) {
    var reactorClass = instance.getDefinition().getReactorClass();
    var selfRef = CUtil.reactorRef(instance);
    for (StateVar stateVar : allStateVars(toDefinition(reactorClass))) {
      if (isInitialized(stateVar)) {
        var mode =
            stateVar.eContainer() instanceof Mode
                ? instance.lookupModeInstance((Mode) stateVar.eContainer())
                : instance.getMode(false);
        initializeTriggerObjects.pr(
            CStateGenerator.generateInitializer(instance, selfRef, stateVar, mode, types));
        if (mode != null && stateVar.isReset()) {
          CUtil.getClosestEnclave(instance).enclaveInfo.numModalResetStates +=
              instance.getTotalWidth();
        }
      }
    }
  }

  /**
   * Generate code to set the deadline field of the reactions in the specified reactor instance.
   *
   * @param instance The reactor instance.
   */
  private void generateSetDeadline(ReactorInstance instance) {
    for (ReactionInstance reaction : instance.reactions) {
      var selfRef = CUtil.reactorRef(reaction.getParent()) + "->_lf__reaction_" + reaction.index;
      if (reaction.declaredDeadline != null) {
        var deadline = reaction.declaredDeadline.maxDelay;
        initializeTriggerObjects.pr(
            selfRef + ".deadline = " + types.getTargetTimeExpr(deadline) + ";");
      } else { // No deadline.
        initializeTriggerObjects.pr(selfRef + ".deadline = NEVER;");
      }
    }
  }

  /**
   * Generate code to initialize modes.
   *
   * @param instance The reactor instance.
   */
  private void generateModeStructure(ReactorInstance instance) {
    CModesGenerator.generateModeStructure(instance, initializeTriggerObjects);
    if (!instance.modes.isEmpty()) {
      CUtil.getClosestEnclave(instance).enclaveInfo.numModalReactors += instance.getTotalWidth();
    }
  }

  /**
   * Generate runtime initialization code for parameters of a given reactor instance
   *
   * @param instance The reactor instance.
   */
  protected void generateParameterInitialization(ReactorInstance instance) {
    var selfRef = CUtil.reactorRef(instance);
    // Set the local bank_index variable so that initializers can use it.
    initializeTriggerObjects.pr(
        "bank_index = "
            + CUtil.bankIndex(instance)
            + ";"
            + " SUPPRESS_UNUSED_WARNING(bank_index);");
    for (ParameterInstance parameter : instance.parameters) {
      // NOTE: we now use the resolved literal value. For better efficiency, we could
      // store constants in a global array and refer to its elements to avoid duplicate
      // memory allocations.
      // NOTE: If the parameter is initialized with a static initializer for an array
      // or struct (the initialization expression is surrounded by { ... }), then we
      // have to declare a static variable to ensure that the memory is put in data space
      // and not on the stack.
      // FIXME: Is there a better way to determine this than the string comparison?
      var initializer = CParameterGenerator.getInitializer(parameter);
      if (initializer.startsWith("{")) {
        var temporaryVariableName = parameter.uniqueID();
        initializeTriggerObjects.pr(
            String.join(
                "\n",
                "static "
                    + types.getVariableDeclaration(
                        instance.tpr, parameter.type, temporaryVariableName, true)
                    + " = "
                    + initializer
                    + ";",
                selfRef + "->" + parameter.getName() + " = " + temporaryVariableName + ";"));
      } else {
        initializeTriggerObjects.pr(
            selfRef + "->" + parameter.getName() + " = " + initializer + ";");
      }
    }
  }

  /**
   * Generate code that mallocs memory for any output multiports.
   *
   * @param reactor The reactor instance.
   */
  private void initializeOutputMultiports(ReactorInstance reactor) {
    var reactorSelfStruct = CUtil.reactorRef(reactor);
    for (PortInstance output : reactor.outputs) {
      initializeTriggerObjects.pr(
          CPortGenerator.initializeOutputMultiport(output, reactorSelfStruct, types));
    }
  }

  /**
   * Allocate memory for inputs.
   *
   * @param reactor The reactor.
   */
  private void initializeInputMultiports(ReactorInstance reactor) {
    var reactorSelfStruct = CUtil.reactorRef(reactor);
    for (PortInstance input : reactor.inputs) {
      initializeTriggerObjects.pr(
          CPortGenerator.initializeInputMultiport(input, reactorSelfStruct));
    }
  }

  @Override
  public TargetTypes getTargetTypes() {
    return types;
  }

  /**
   * Get the Docker generator.
   *
   * @param context
   * @return
   */
  @Override
  protected DockerGenerator getDockerGenerator(LFGeneratorContext context) {
    return new CDockerGenerator(context);
  }

  // //////////////////////////////////////////
  // // Protected methods.

  // Perform set up that does not generate code. Return false on failure.
  protected boolean setUpGeneralParameters() {
    accommodatePhysicalActionsIfPresent();
    CompileDefinitionsProperty.INSTANCE.update(
        targetConfig,
        Map.of("LOG_LEVEL", String.valueOf(targetConfig.get(LoggingProperty.INSTANCE).ordinal())));

    targetConfig.compileAdditionalSources.addAll(CCoreFilesUtils.getCTargetSrc());
    // Create the main reactor instance if there is a main reactor.
    this.main =
        ASTUtils.createMainReactorInstance(mainDef, reactors, messageReporter, targetConfig);
    if (this.main == null) {
      // Something went wrong (causality cycle?). Stop.
      return false;
    }
    if (hasModalReactors) {
      // So that each separate compile knows about modal reactors, do this:
      CompileDefinitionsProperty.INSTANCE.update(targetConfig, Map.of("MODAL_REACTORS", "TRUE"));
    }
    if (!targetConfig.get(SingleThreadedProperty.INSTANCE)) {
      CompileDefinitionsProperty.INSTANCE.update(
          targetConfig,
          Map.of(
              "SCHEDULER", targetConfig.get(SchedulerProperty.INSTANCE).getSchedulerCompileDef(),
              "NUMBER_OF_WORKERS", String.valueOf(targetConfig.get(WorkersProperty.INSTANCE))));
    }
    if (targetConfig.isSet(PlatformProperty.INSTANCE)) {

      final var platformOptions = targetConfig.get(PlatformProperty.INSTANCE);
      if (!targetConfig.get(SingleThreadedProperty.INSTANCE)
          && platformOptions.platform() == Platform.ARDUINO
          && (!platformOptions.board().setByUser()
              || !platformOptions.board().value().contains("mbed"))) {
        // non-MBED boards should not use threading
        messageReporter
            .nowhere()
            .info(
                "Threading is incompatible with plain (non-MBED) Arduino. Setting threading to"
                    + " false.");
        SingleThreadedProperty.INSTANCE.override(targetConfig, true);
      }

      if (platformOptions.platform() == Platform.ARDUINO
          && !targetConfig.get(NoCompileProperty.INSTANCE)
          && !platformOptions.board().setByUser()) {
        messageReporter
            .nowhere()
            .info(
                "To enable compilation for the Arduino platform, you must specify the"
                    + " fully-qualified board name (FQBN) in the target property. For example,"
                    + " platform: {name: arduino, board: arduino:avr:leonardo}. Entering"
                    + " \"no-compile\" mode and generating target code only.");
        NoCompileProperty.INSTANCE.override(targetConfig, true);
      }

      if (platformOptions.platform() == Platform.ZEPHYR
          && !targetConfig.get(SingleThreadedProperty.INSTANCE)
          && platformOptions.userThreads().value() >= 0) {
        targetConfig
            .get(CompileDefinitionsProperty.INSTANCE)
            .put(
                PlatformOption.USER_THREADS.name(),
                String.valueOf(platformOptions.userThreads().value()));
      }
      pickCompilePlatform();
    }
    return true;
  }

  protected void handleProtoFiles() {
    // Handle .proto files.
    for (String file : targetConfig.get(ProtobufsProperty.INSTANCE)) {
      this.processProtoFile(file);
    }
  }

  /**
   * Generate code that needs to appear at the top of the generated C file, such as #define and
   * #include statements.
   */
  public String generateDirectives() {
    CodeBuilder code = new CodeBuilder();
    code.prComment("Code generated by the Lingua Franca compiler from:");
    code.prComment("file:/" + FileUtil.toUnixString(fileConfig.srcFile));
    code.pr(CPreambleGenerator.generateDefineDirectives(targetConfig, fileConfig.getSrcGenPath()));
    code.pr(CPreambleGenerator.generateIncludeStatements(targetConfig, cppMode));
    return code.toString();
  }

  /** Generate top-level preamble code. */
  protected String generateTopLevelPreambles(Reactor reactor) {
    // Reactors that are instantiated or inherited by the specified reactor need to have
    // their file-level preambles included. Avoid including the same preamble multiple times.
    var visited = new LinkedHashSet<EObject>();
    return generateTopLevelPreambles(reactor, visited);
  }

  private String generateTopLevelPreambles(Reactor reactor, Set<EObject> visited) {
    if (visited.contains(reactor.eContainer())) {
      // If we have already visited the container of this reactor, then we do not need to
      // generate the preamble again.
      return "";
    }

    CodeBuilder builder = new CodeBuilder();

    // First, generate the preambles for the base classes of the specified reactor.
    var superClasses = reactor.getSuperClasses();
    if (superClasses != null) {
      for (var superClass : superClasses) {
        builder.pr(generateTopLevelPreambles(toDefinition(superClass), visited));
      }
    }
    // These could have been in the same file, in which case we avoid generating again.
    if (!visited.contains(reactor.eContainer())) {
      visited.add(reactor.eContainer());
      // Generate the preambles for the specified reactor.
      // We need to guard it with a #ifndef.
      // The eContainer() of the reactor is the file in which it is defined, which
      // is where top-level preambles reside. Hence, guard the preamble with an
      // identifier unique to the file.
      var preambles = ((Model) reactor.eContainer()).getPreambles();
      var hasPreamble =
          !preambles.isEmpty() || targetConfig.get(ProtobufsProperty.INSTANCE).size() > 0;
      if (hasPreamble) {
        var guard = "TOP_LEVEL_PREAMBLE_" + reactor.eContainer().hashCode() + "_H";
        builder.pr("#ifndef " + guard);
        builder.pr("#define " + guard);

        for (var preamble : preambles) {
          var code = preamble.getCode();
          if (code != null) {
            var text = toText(code);
            builder.pr(text);
          }
        }

        // Also generate the preambles for all the .proto files that are used.
        for (String file : targetConfig.get(ProtobufsProperty.INSTANCE)) {
          var dotIndex = file.lastIndexOf(".");
          var rootFilename = file;
          if (dotIndex > 0) {
            rootFilename = file.substring(0, dotIndex);
          }
          code.pr("#include " + addDoubleQuotes(rootFilename + ".pb-c.h"));
          builder.pr("#include " + addDoubleQuotes(rootFilename + ".pb-c.h"));
        }

        builder.pr("#endif // " + guard);
      }
    }
    // Finally, generate the preambles for all the reactors that are instantiated.
    for (var instantiation : ASTUtils.allInstantiations(reactor)) {
      var instantiated = toDefinition(instantiation.getReactorClass());
      builder.pr(generateTopLevelPreambles(toDefinition(instantiated), visited));
    }
    return builder.toString();
  }

  protected boolean targetLanguageIsCpp() {
    return cppMode;
  }

  /**
   * Given a line of text from the output of a compiler, return an instance of ErrorFileAndLine if
   * the line is recognized as the first line of an error message. Otherwise, return null.
   *
   * @param line A line of output from a compiler or other external tool that might generate errors.
   * @return If the line is recognized as the start of an error message, then return a class
   *     containing the path to the file on which the error occurred (or null if there is none), the
   *     line number (or the string "1" if there is none), the character position (or the string "0"
   *     if there is none), and the message (or an empty string if there is none).
   */
  @Override
  public ErrorFileAndLine parseCommandOutput(String line) {
    var matcher = compileErrorPattern.matcher(line);
    if (matcher.find()) {
      var result = new ErrorFileAndLine();
      result.filepath = matcher.group("path");
      result.line = matcher.group("line");
      result.character = matcher.group("column");
      result.message = matcher.group("message");

      if (!result.message.toLowerCase().contains("error:")) {
        result.isError = false;
      }
      return result;
    }
    return null;
  }

  ////////////////////////////////////////////
  //// Private methods.

  /** Returns the Target enum for this generator */
  @Override
  public Target getTarget() {
    return Target.C;
  }

  ////////////////////////////////////////////////////////////
  //// Private methods

  /**
   * Generate an array of self structs for the reactor and one for each of its children.
   *
   * @param r The reactor instance.
   */
  private void generateSelfStructs(ReactorInstance r) {
    initializeTriggerObjects.pr(
        CUtil.selfType(r) + "* " + CUtil.reactorRefName(r) + "[" + r.getTotalWidth() + "];");
    initializeTriggerObjects.pr("SUPPRESS_UNUSED_WARNING(" + CUtil.reactorRefName(r) + ");");
    for (ReactorInstance child : r.children) {
      generateSelfStructs(child);
    }
  }

  private Stream<TypeParameterizedReactor> allTypeParameterizedReactors() {
    return ASTUtils.recursiveChildren(main).stream().map(it -> it.tpr).distinct();
  }
}
