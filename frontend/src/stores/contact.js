import { defineStore } from 'pinia'
import { ref } from 'vue'
import { friendApi, groupApi } from '@/api'

export const useContactStore = defineStore('contact', () => {
  const friends = ref([])
  const groups = ref([])
  const friendRequests = ref([])

  async function fetchFriends() {
    const res = await friendApi.getList()
    friends.value = res.data
    return res.data
  }

  async function fetchGroups() {
    const res = await groupApi.getList()
    groups.value = res.data
    return res.data
  }

  async function fetchPendingRequests() {
    const res = await friendApi.getPendingRequests()
    friendRequests.value = res.data
    return res.data
  }

  async function sendFriendRequest(friendId) {
    return await friendApi.sendRequest(friendId)
  }

  async function sendFriendRequestByUsername(username) {
    return await friendApi.sendRequestByUsername(username)
  }

  async function acceptFriendRequest(friendId) {
    await friendApi.acceptRequest(friendId)
    await fetchFriends()
    await fetchPendingRequests()
  }

  async function rejectFriendRequest(friendId) {
    await friendApi.rejectRequest(friendId)
    await fetchPendingRequests()
  }

  async function deleteFriend(friendId) {
    await friendApi.deleteFriend(friendId)
    await fetchFriends()
  }

  async function createGroup(data) {
    return await groupApi.create(data)
  }

  async function joinGroup(groupId) {
    return await groupApi.join(groupId)
  }

  async function joinGroupByName(groupName) {
    return await groupApi.joinByName(groupName)
  }

  async function joinGroupByNo(groupNo) {
    return await groupApi.joinByNo(groupNo)
  }

  function updateFriendOnlineStatus(userId, isOnline) {
    const friend = friends.value.find(f => String(f.id) === String(userId))
    if (friend) {
      friend.online = isOnline
    }
  }

  return {
    friends,
    groups,
    friendRequests,
    fetchFriends,
    fetchGroups,
    fetchPendingRequests,
    sendFriendRequest,
    sendFriendRequestByUsername,
    acceptFriendRequest,
    rejectFriendRequest,
    deleteFriend,
    createGroup,
    joinGroup,
    joinGroupByName,
    joinGroupByNo,
    updateFriendOnlineStatus
  }
})
