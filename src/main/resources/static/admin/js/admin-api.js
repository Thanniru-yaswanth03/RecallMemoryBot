/**
 * Admin API Client for RecallMemoryBot
 */
const AdminApi = (() => {
    const TOKEN_KEY = 'recall_admin_token';
    const USER_KEY = 'recall_admin_user';

    async function request(endpoint, options = {}) {
        const token = localStorage.getItem(TOKEN_KEY);
        const headers = {
            'Content-Type': 'application/json',
            ...(options.headers || {})
        };

        if (token) {
            headers['Authorization'] = `Bearer ${token}`;
            headers['X-Admin-Token'] = token;
        }

        const config = {
            ...options,
            headers
        };

        const response = await fetch(endpoint, config);

        if (response.status === 401) {
            localStorage.removeItem(TOKEN_KEY);
            localStorage.removeItem(USER_KEY);
            if (!window.location.pathname.includes('login.html')) {
                window.location.href = '/admin/login.html';
            }
            let errorMsg = 'Invalid username or password';
            try {
                const errData = await response.json();
                if (errData && errData.message) {
                    errorMsg = errData.message;
                }
            } catch (e) {
                // Not JSON
            }
            throw new Error(errorMsg);
        }

        if (!response.ok) {
            let errorMsg = `Request failed: ${response.status} ${response.statusText}`;
            try {
                const errData = await response.json();
                if (errData && errData.message) {
                    errorMsg = errData.message;
                }
            } catch (e) {
                // Not JSON
            }
            throw new Error(errorMsg);
        }

        return response.json();
    }

    return {
        async login(username, password) {
            return request('/api/admin/auth/login', {
                method: 'POST',
                body: JSON.stringify({ username, password })
            });
        },

        async logout() {
            try {
                await request('/api/admin/auth/logout', { method: 'POST' });
            } finally {
                localStorage.removeItem(TOKEN_KEY);
                localStorage.removeItem(USER_KEY);
                window.location.href = '/admin/login.html';
            }
        },

        async getMe() {
            return request('/api/admin/auth/me');
        },

        async getDashboard() {
            return request('/api/admin/dashboard');
        },

        async getGroups(page = 0, size = 20, search = '', active = null) {
            const params = new URLSearchParams({ page, size });
            if (search) params.append('search', search);
            if (active !== null) params.append('active', active);
            return request(`/api/admin/groups?${params.toString()}`);
        },

        async getGroupDetail(id) {
            return request(`/api/admin/groups/${id}`);
        },

        async getGroupMembers(id, page = 0, size = 20) {
            return request(`/api/admin/groups/${id}/members?page=${page}&size=${size}`);
        },

        async getMessages(page = 0, size = 20, groupId = null, userId = null, search = '') {
            const params = new URLSearchParams({ page, size });
            if (groupId) params.append('groupId', groupId);
            if (userId) params.append('userId', userId);
            if (search) params.append('search', search);
            return request(`/api/admin/messages?${params.toString()}`);
        },

        async getMemories(page = 0, size = 20, groupId = null, type = '', search = '') {
            const params = new URLSearchParams({ page, size });
            if (groupId) params.append('groupId', groupId);
            if (type) params.append('type', type);
            if (search) params.append('search', search);
            return request(`/api/admin/memories?${params.toString()}`);
        },

        async getActivity(limit = 50, type = '', groupId = null) {
            const params = new URLSearchParams({ limit });
            if (type) params.append('type', type);
            if (groupId) params.append('groupId', groupId);
            return request(`/api/admin/activity?${params.toString()}`);
        },

        async getSystemHealth() {
            return request('/api/admin/system/health');
        }
    };
})();
