package com.nuvio.app.platform.gamepad

/**
 * Standard gamepad buttons (Xbox/DualSense/Steam layout)
 * Matches SDL_GameControllerButton enum
 */
enum class GamepadButton {
    DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT,
    SOUTH, // A (Xbox), Cross (PlayStation), A (Steam) - SDL_CONTROLLER_BUTTON_A
    EAST,  // B (Xbox), Circle (PlayStation), B (Steam) - SDL_CONTROLLER_BUTTON_B
    NORTH, // X (Xbox), Triangle (PlayStation), X (Steam) - SDL_CONTROLLER_BUTTON_X
    WEST,  // Y (Xbox), Square (PlayStation), Y (Steam) - SDL_CONTROLLER_BUTTON_Y
    LEFT_SHOULDER, RIGHT_SHOULDER,  // SDL_CONTROLLER_BUTTON_LEFTSHOULDER, RIGHTSHOULDER
    LEFT_TRIGGER, RIGHT_TRIGGER,    // SDL_CONTROLLER_BUTTON_LEFTTRIGGER, RIGHTTRIGGER
    LEFT_STICK, RIGHT_STICK,        // SDL_CONTROLLER_BUTTON_LEFTSTICK, RIGHTSTICK
    START, BACK, GUIDE              // SDL_CONTROLLER_BUTTON_START, BACK, GUIDE
}

/**
 * Gamepad analog axes
 * Matches SDL_GameControllerAxis enum
 */
enum class GamepadAxis {
    LEFT_X, LEFT_Y, RIGHT_X, RIGHT_Y, LEFT_TRIGGER, RIGHT_TRIGGER
}

/**
 * Direction for focus navigation
 */
enum class Direction {
    Up, Down, Left, Right
}

/**
 * Gamepad event types for navigation and player controls
 */
sealed interface GamepadEvent {
    data class ButtonPressed(val button: GamepadButton, val controllerIndex: Int) : GamepadEvent
    data class ButtonReleased(val button: GamepadButton, val controllerIndex: Int) : GamepadEvent
    data class AxisMoved(val axis: GamepadAxis, val value: Float, val controllerIndex: Int) : GamepadEvent
}