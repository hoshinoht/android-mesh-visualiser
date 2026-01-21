package com.example.meshvisualiser.models

/**
 * Types of messages exchanged in the mesh network.
 */
enum class MessageType(val value: Int) {
    /** Initial handshake to exchange peer IDs */
    HANDSHAKE(0),
    
    /** Election message - "I want to lead" */
    ELECTION(1),
    
    /** OK response - "I'm higher, I'll take over" */
    OK(2),
    
    /** Coordinator announcement with Cloud Anchor ID */
    COORDINATOR(3),
    
    /** Pose update with relative coordinates */
    POSE_UPDATE(4);
    
    companion object {
        fun fromValue(value: Int): MessageType? = entries.find { it.value == value }
    }
}
