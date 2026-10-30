package com.nuvio.app.platform.gamepad

import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * SDL2 GameController wrapper using libsdl4j (JNA bindings)
 */
private object SDL {
    // SDL_Init flags
    const val SDL_INIT_GAMECONTROLLER = 0x00002000
    const val SDL_INIT_JOYSTICK = 0x00000200
    const val SDL_INIT_HAPTIC = 0x00001000
    
    // SDL_GameControllerButton enum values (from SDL_gamecontroller.h)
    const val SDL_CONTROLLER_BUTTON_A = 0
    const val SDL_CONTROLLER_BUTTON_B = 1
    const val SDL_CONTROLLER_BUTTON_X = 2
    const val SDL_CONTROLLER_BUTTON_Y = 3
    const val SDL_CONTROLLER_BUTTON_BACK = 4
    const val SDL_CONTROLLER_BUTTON_GUIDE = 5
    const val SDL_CONTROLLER_BUTTON_START = 6
    const val SDL_CONTROLLER_BUTTON_LEFTSTICK = 7
    const val SDL_CONTROLLER_BUTTON_RIGHTSTICK = 8
    const val SDL_CONTROLLER_BUTTON_LEFTSHOULDER = 9
    const val SDL_CONTROLLER_BUTTON_RIGHTSHOULDER = 10
    const val SDL_CONTROLLER_BUTTON_DPAD_UP = 11
    const val SDL_CONTROLLER_BUTTON_DPAD_DOWN = 12
    const val SDL_CONTROLLER_BUTTON_DPAD_LEFT = 13
    const val SDL_CONTROLLER_BUTTON_DPAD_RIGHT = 14
    const val SDL_CONTROLLER_BUTTON_LEFTTRIGGER = 15  // Note: treated as button when > threshold
    const val SDL_CONTROLLER_BUTTON_RIGHTTRIGGER = 16 // Note: treated as button when > threshold
    
    // SDL_GameControllerAxis enum values
    const val SDL_CONTROLLER_AXIS_LEFTX = 0
    const val SDL_CONTROLLER_AXIS_LEFTY = 1
    const val SDL_CONTROLLER_AXIS_RIGHTX = 2
    const val SDL_CONTROLLER_AXIS_RIGHTY = 3
    const val SDL_CONTROLLER_AXIS_TRIGGERLEFT = 4
    const val SDL_CONTROLLER_AXIS_TRIGGERRIGHT = 5
    
    // JNA Library loading
    private val lib = Native.load("SDL2", SdlLibrary::class.java)
    
    interface SdlLibrary : com.sun.jna.Library {
        fun SDL_Init(flags: Int): Int
        fun SDL_Quit()
        fun SDL_NumJoysticks(): Int
        fun SDL_IsGameController(joystick_index: Int): Boolean
        fun SDL_GameControllerOpen(joystick_index: Int): Pointer
        fun SDL_GameControllerClose(gamecontroller: Pointer)
        fun SDL_GameControllerGetButton(gamecontroller: Pointer, button: Int): Byte
        fun SDL_GameControllerGetAxis(gamecontroller: Pointer, axis: Int): Short
        fun SDL_GameControllerGetAttached(gamecontroller: Pointer): Boolean
        fun SDL_GameControllerName(gamecontroller: Pointer): String
        fun SDL_GameControllerGetJoystick(gamecontroller: Pointer): Pointer
        fun SDL_JoystickInstanceID(joystick: Pointer): Int
        fun SDL_GameControllerAddMappingsFromFile(mappingFile: String): Int
    }
    
    fun SDL_Init(flags: Int): Int = lib.SDL_Init(flags)
    fun SDL_Quit() = lib.SDL_Quit()
    fun SDL_NumJoysticks(): Int = lib.SDL_NumJoysticks()
    fun SDL_IsGameController(joystick_index: Int): Boolean = lib.SDL_IsGameController(joystick_index)
    fun SDL_GameControllerOpen(joystick_index: Int): Pointer = lib.SDL_GameControllerOpen(joystick_index)
    fun SDL_GameControllerClose(gamecontroller: Pointer) = lib.SDL_GameControllerClose(gamecontroller)
    fun SDL_GameControllerGetButton(gamecontroller: Pointer, button: Int): Boolean = lib.SDL_GameControllerGetButton(gamecontroller, button).toInt() != 0
    fun SDL_GameControllerGetAxis(gamecontroller: Pointer, axis: Int): Float = lib.SDL_GameControllerGetAxis(gamecontroller, axis).toFloat() / 32767.0f
    fun SDL_GameControllerGetAttached(gamecontroller: Pointer): Boolean = lib.SDL_GameControllerGetAttached(gamecontroller)
    fun SDL_GameControllerName(gamecontroller: Pointer): String = lib.SDL_GameControllerName(gamecontroller)
    fun SDL_GameControllerGetJoystick(gamecontroller: Pointer): Pointer = lib.SDL_GameControllerGetJoystick(gamecontroller)
    fun SDL_JoystickInstanceID(joystick: Pointer): Int = lib.SDL_JoystickInstanceID(joystick)
    fun SDL_GameControllerAddMappingsFromFile(mappingFile: String): Int = lib.SDL_GameControllerAddMappingsFromFile(mappingFile)
}

/**
 * Manages gamepad detection, polling, and event emission.
 * Uses libsdl4j (SDL2 JNA bindings) for cross-platform gamepad support.
 */
class GamepadManager private constructor(
    private val scope: CoroutineScope,
    private val pollingIntervalMs: Long = 16 // ~60fps
) {
    private val buttonEvents = Channel<GamepadEvent>(Channel.UNLIMITED)
    private val axisEvents = Channel<GamepadEvent>(Channel.UNLIMITED)
    private val connectedControllers = MutableStateFlow<Int>(0)
    private var controllers = mutableMapOf<Int, ControllerData>()
    private var pollingJob: kotlinx.coroutines.Job? = null
    private var sdlInitialized = false

    val buttonEventsFlow = kotlinx.coroutines.flow.channelFlow<GamepadEvent> {
        while (true) {
            send(buttonEvents.receive())
        }
    }
    val axisEventsFlow = kotlinx.coroutines.flow.channelFlow<GamepadEvent> {
        while (true) {
            send(axisEvents.receive())
        }
    }
    val connectedControllersFlow = connectedControllers.asStateFlow()

    companion object {
        @Volatile private var INSTANCE: GamepadManager? = null
        fun getInstance(scope: CoroutineScope = CoroutineScope(Dispatchers.Default)): GamepadManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GamepadManager(scope).also { INSTANCE = it }
            }
        }
    }

    private data class ControllerData(
        val pointer: Pointer,
        val instanceId: Int,
        var prevButtons: MutableMap<Int, Boolean>,
        var prevAxes: MutableMap<Int, Float>
    )

    init {
        initializeSDL()
        scanControllers()
        startPolling()
    }

    private fun initializeSDL() {
        if (sdlInitialized) return
        val result = SDL.SDL_Init(SDL.SDL_INIT_GAMECONTROLLER or SDL.SDL_INIT_JOYSTICK or SDL.SDL_INIT_HAPTIC)
        if (result < 0) {
            // SDL initialization failed, but continue anyway
        }
        sdlInitialized = true
        
        // Load gamecontrollerdb.txt for better controller mappings
        // This would typically be loaded from resources
    }

    private fun scanControllers() {
        val numJoysticks = SDL.SDL_NumJoysticks()
        var count = 0
        
        for (i in 0 until numJoysticks) {
            if (SDL.SDL_IsGameController(i)) {
                val controllerPtr = SDL.SDL_GameControllerOpen(i)
                if (controllerPtr != Pointer.NULL) {
                    val joystickPtr = SDL.SDL_GameControllerGetJoystick(controllerPtr)
                    val instanceId = SDL.SDL_JoystickInstanceID(joystickPtr)
                    
                    // Initialize previous state maps
                    val prevButtons = mutableMapOf<Int, Boolean>()
                    val prevAxes = mutableMapOf<Int, Float>()
                    
                    // Initialize all buttons to false
                    for (btn in 0..16) prevButtons[btn] = false
                    // Initialize all axes to 0f
                    for (ax in 0..5) prevAxes[ax] = 0f
                    
                    controllers[instanceId] = ControllerData(controllerPtr, instanceId, prevButtons, prevAxes)
                    count++
                }
            }
        }
        
        connectedControllers.value = count
    }

    private fun startPolling() {
        pollingJob = scope.launch(Dispatchers.Default) {
            while (true) {
                pollControllers()
                delay(pollingIntervalMs)
            }
        }
    }

    private fun pollControllers() {
        // Check for disconnected controllers
        val toRemove = mutableListOf<Int>()
        controllers.forEach { (instanceId, data) ->
            if (!SDL.SDL_GameControllerGetAttached(data.pointer)) {
                toRemove.add(instanceId)
            }
        }
        toRemove.forEach { instanceId ->
            controllers.remove(instanceId)?.pointer?.let { SDL.SDL_GameControllerClose(it) }
        }
        if (toRemove.isNotEmpty()) {
            connectedControllers.value = controllers.size
        }
        
        // Scan for new controllers
        val numJoysticks = SDL.SDL_NumJoysticks()
        for (i in 0 until numJoysticks) {
            if (SDL.SDL_IsGameController(i)) {
                val controllerPtr = SDL.SDL_GameControllerOpen(i)
                if (controllerPtr != Pointer.NULL) {
                    val joystickPtr = SDL.SDL_GameControllerGetJoystick(controllerPtr)
                    val instanceId = SDL.SDL_JoystickInstanceID(joystickPtr)
                    
                    if (!controllers.containsKey(instanceId)) {
                        val prevButtons = mutableMapOf<Int, Boolean>()
                        val prevAxes = mutableMapOf<Int, Float>()
                        for (btn in 0..16) prevButtons[btn] = false
                        for (ax in 0..5) prevAxes[ax] = 0f
                        
                        controllers[instanceId] = ControllerData(controllerPtr, instanceId, prevButtons, prevAxes)
                        connectedControllers.value = controllers.size
                    } else {
                        // Already tracked, close the duplicate
                        SDL.SDL_GameControllerClose(controllerPtr)
                    }
                }
            }
        }
        
        // Poll existing controllers for changes
        controllers.forEach { (instanceId, data) ->
            pollController(instanceId, data)
        }
    }

    private fun pollController(instanceId: Int, data: ControllerData) {
        // Poll buttons
        val buttonMap = mapOf(
            GamepadButton.DPAD_UP to SDL.SDL_CONTROLLER_BUTTON_DPAD_UP,
            GamepadButton.DPAD_DOWN to SDL.SDL_CONTROLLER_BUTTON_DPAD_DOWN,
            GamepadButton.DPAD_LEFT to SDL.SDL_CONTROLLER_BUTTON_DPAD_LEFT,
            GamepadButton.DPAD_RIGHT to SDL.SDL_CONTROLLER_BUTTON_DPAD_RIGHT,
            GamepadButton.SOUTH to SDL.SDL_CONTROLLER_BUTTON_A,
            GamepadButton.EAST to SDL.SDL_CONTROLLER_BUTTON_B,
            GamepadButton.NORTH to SDL.SDL_CONTROLLER_BUTTON_X,
            GamepadButton.WEST to SDL.SDL_CONTROLLER_BUTTON_Y,
            GamepadButton.LEFT_SHOULDER to SDL.SDL_CONTROLLER_BUTTON_LEFTSHOULDER,
            GamepadButton.RIGHT_SHOULDER to SDL.SDL_CONTROLLER_BUTTON_RIGHTSHOULDER,
            GamepadButton.LEFT_TRIGGER to SDL.SDL_CONTROLLER_BUTTON_LEFTTRIGGER,
            GamepadButton.RIGHT_TRIGGER to SDL.SDL_CONTROLLER_BUTTON_RIGHTTRIGGER,
            GamepadButton.LEFT_STICK to SDL.SDL_CONTROLLER_BUTTON_LEFTSTICK,
            GamepadButton.RIGHT_STICK to SDL.SDL_CONTROLLER_BUTTON_RIGHTSTICK,
            GamepadButton.START to SDL.SDL_CONTROLLER_BUTTON_START,
            GamepadButton.BACK to SDL.SDL_CONTROLLER_BUTTON_BACK,
            GamepadButton.GUIDE to SDL.SDL_CONTROLLER_BUTTON_GUIDE,
        )
        
        buttonMap.forEach { (gamepadButton, sdlButton) ->
            val isPressed = SDL.SDL_GameControllerGetButton(data.pointer, sdlButton)
            val wasPressed = data.prevButtons[sdlButton] ?: false
            data.prevButtons[sdlButton] = isPressed
            
            if (isPressed && !wasPressed) {
                buttonEvents.trySend(GamepadEvent.ButtonPressed(gamepadButton, instanceId))
            } else if (!isPressed && wasPressed) {
                buttonEvents.trySend(GamepadEvent.ButtonReleased(gamepadButton, instanceId))
            }
        }
        
        // Poll axes
        val axisMap = mapOf(
            GamepadAxis.LEFT_X to SDL.SDL_CONTROLLER_AXIS_LEFTX,
            GamepadAxis.LEFT_Y to SDL.SDL_CONTROLLER_AXIS_LEFTY,
            GamepadAxis.RIGHT_X to SDL.SDL_CONTROLLER_AXIS_RIGHTX,
            GamepadAxis.RIGHT_Y to SDL.SDL_CONTROLLER_AXIS_RIGHTY,
            GamepadAxis.LEFT_TRIGGER to SDL.SDL_CONTROLLER_AXIS_TRIGGERLEFT,
            GamepadAxis.RIGHT_TRIGGER to SDL.SDL_CONTROLLER_AXIS_TRIGGERRIGHT,
        )
        
        val threshold = 0.05f // ~1.6% deadzone
        axisMap.forEach { (gamepadAxis, sdlAxis) ->
            val value = SDL.SDL_GameControllerGetAxis(data.pointer, sdlAxis)
            val prevValue = data.prevAxes[sdlAxis] ?: 0f
            data.prevAxes[sdlAxis] = value
            
            if (abs(value - prevValue) > threshold) {
                axisEvents.trySend(GamepadEvent.AxisMoved(gamepadAxis, value, instanceId))
            }
        }
    }

    fun shutdown() {
        pollingJob?.cancel()
        controllers.values.forEach { data ->
            SDL.SDL_GameControllerClose(data.pointer)
        }
        controllers.clear()
        if (sdlInitialized) {
            SDL.SDL_Quit()
            sdlInitialized = false
        }
    }
    
    fun isControllerConnected(): Boolean = connectedControllers.value > 0
}