import api from '@/utils/request'

export const authApi = {
  register(data) {
    return api.post('/auth/register', data)
  },

  login(data) {
    return api.post('/auth/login', data)
  },

  refresh(refreshToken) {
    return api.post('/auth/refresh', { refreshToken })
  },

  getUserInfo(userId) {
    return api.get(`/user/${userId}`)
  },

  searchUserByUsername(username) {
    return api.get('/auth/user/search', { params: { username } })
  }
}

export const friendApi = {
  sendRequest(friendId) {
    return api.post(`/friend/apply/${friendId}`)
  },

  sendRequestByUsername(username) {
    return api.post('/friend/apply/username', null, { params: { username } })
  },

  acceptRequest(friendId) {
    return api.post(`/friend/accept/${friendId}`)
  },

  rejectRequest(friendId) {
    return api.post(`/friend/reject/${friendId}`)
  },

  getList() {
    return api.get('/friend/list')
  },

  getPendingRequests() {
    return api.get('/friend/requests/pending')
  },

  deleteFriend(friendId) {
    return api.delete(`/friend/${friendId}`)
  }
}

export const groupApi = {
  create(data) {
    return api.post('/group', data)
  },

  join(groupId) {
    return api.post(`/group/join/${groupId}`)
  },

  joinByName(groupName) {
    return api.post('/group/join/by-name', null, { params: { groupName } })
  },

  joinByNo(groupNo) {
    return api.post('/group/join/by-no', null, { params: { groupNo } })
  },

  getList() {
    return api.get('/group/list')
  },

  getInfo(groupId) {
    return api.get(`/group/${groupId}`)
  },

  updateNotice(groupId, notice) {
    return api.post(`/group/${groupId}/notice`, { notice })
  },

  getMembers(groupId) {
    return api.get(`/group/${groupId}/members`)
  },

  quit(groupId) {
    return api.delete(`/group/${groupId}/quit`)
  },

  invite(groupId, memberIds) {
    return api.post(`/group/${groupId}/invite`, memberIds)
  },

  inviteByUsername(groupId, usernames) {
    return api.post(`/group/${groupId}/invite/by-username`, usernames)
  },

  kick(groupId, targetId) {
    return api.delete(`/group/${groupId}/kick/${targetId}`)
  },

  transferOwnership(groupId, newOwnerId) {
    return api.post(`/group/${groupId}/transfer/${newOwnerId}`)
  },

  dissolve(groupId) {
    return api.delete(`/group/${groupId}`)
  }
}

export const messageApi = {
  getConversationSummaries() {
    return api.get('/message/conversations')
  },

  getPrivateHistory(params) {
    return api.get('/message/history/private', { params })
  },

  getGroupHistory(params) {
    return api.get('/message/history/group', { params })
  },

  searchMessages(data) {
    return api.post('/message/search', data)
  },

  getMessageContext(data) {
    return api.post('/message/context', data)
  },

  getOfflineMessages() {
    return api.get('/message/offline')
  },

  syncMessages(cursors) {
    return api.post('/message/sync', cursors)
  },

  markAsRead(msgId) {
    return api.post(`/message/read/${msgId}`)
  },

  markConversationAsRead(params) {
    return api.post('/message/read/conversation', null, { params })
  }
}

export const fileApi = {
  upload(file) {
    const formData = new FormData()
    formData.append('file', file)
    return api.post('/file/upload', formData, {
      headers: {
        'Content-Type': 'multipart/form-data'
      }
    })
  },

  getMeta(fileId) {
    return api.get(`/files/${fileId}/meta`)
  }
}
