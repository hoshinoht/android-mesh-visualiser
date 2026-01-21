package com.example.meshvisualiser.models

import com.google.gson.Gson

/** Serializable message exchanged between peers in the mesh network. */
data class MeshMessage(val type: Int, val senderId: Long, val data: String = "") {
  companion object {
    private val gson = Gson()

    fun fromBytes(bytes: ByteArray): MeshMessage? {
      return try {
        gson.fromJson(String(bytes, Charsets.UTF_8), MeshMessage::class.java)
      } catch (e: Exception) {
        null
      }
    }

    fun handshake(localId: Long): MeshMessage {
      return MeshMessage(MessageType.HANDSHAKE.value, localId)
    }

    fun election(localId: Long): MeshMessage {
      return MeshMessage(MessageType.ELECTION.value, localId)
    }

    fun ok(localId: Long): MeshMessage {
      return MeshMessage(MessageType.OK.value, localId)
    }

    fun coordinator(localId: Long, cloudAnchorId: String): MeshMessage {
      return MeshMessage(MessageType.COORDINATOR.value, localId, cloudAnchorId)
    }

    fun poseUpdate(localId: Long, x: Float, y: Float, z: Float): MeshMessage {
      return MeshMessage(MessageType.POSE_UPDATE.value, localId, "$x,$y,$z")
    }
  }

  fun toBytes(): ByteArray {
    return gson.toJson(this).toByteArray(Charsets.UTF_8)
  }

  fun getMessageType(): MessageType? = MessageType.fromValue(type)

  /**
   * Parse pose data from POSE_UPDATE message.
   * @return Triple of (x, y, z) or null if parsing fails
   */
  fun parsePoseData(): Triple<Float, Float, Float>? {
    return try {
      val parts = data.split(",")
      if (parts.size == 3) {
        Triple(parts[0].toFloat(), parts[1].toFloat(), parts[2].toFloat())
      } else null
    } catch (e: Exception) {
      null
    }
  }
}
