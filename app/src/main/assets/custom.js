// assets/advanced-cache-control.js
(function() {
    'use strict';

    const cacheConfig = {
        cacheableExtensions: ['css', 'js', 'png', 'jpg', 'jpeg', 'gif', 'webp', 'woff', 'woff2', 'ttf', 'eot', 'svg', 'ico'],
        cacheableDomains: ['cdn.', 'static.', 'assets.', 'img.', 'image.'],
        cacheablePaths: ['/static/', '/assets/', '/images/', '/css/', '/js/', '/fonts/'],
        nonCacheableApis: ['/api/', '/login', '/logout', '/auth', '/token']
    };

    // 更精确的 RESTful 路径检测
    function isRestfulPath(url) {
        try {
            const urlObj = new URL(url, window.location.origin);
            const pathname = urlObj.pathname;

            // RESTful 路径特征：
            // 1. 包含数字ID的路径（如 /api/users/123）
            if (/\/(\d+)$/.test(pathname)) return true;

            // 2. 包含UUID的路径（如 /api/users/550e8400-e29b-41d4-a716-446655440000）
            if (/\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(pathname)) return true;

            // 3. 特定端点（如 /graphql, /upload 等）
            if (/^\/(graphql|upload|webhook|callback)$/i.test(pathname)) return true;

            // 4. 不包含查询参数的 API 路径
            if (pathname.includes('/api/') && !urlObj.search) {
                const segments = pathname.split('/');
                // 如果路径段数较多，可能是 RESTful 资源路径
                if (segments.length >= 4) return true;
            }

            return false;
        } catch (e) {
            // URL 解析失败，使用简单检测
            return !url.includes('?') && url.match(/\/api\/[^/?]+\/[^/?]+$/);
        }
    }

    function isQueryStringUrl(url) {
        return url.includes('?') || (url.includes('&') && !url.includes('/api/'));
    }

    function safelyAddCacheBuster(url) {
        if (isRestfulPath(url)) {
            return { url, useHeaders: true };
        }

        if (isQueryStringUrl(url)) {
            return {
                url: url + (url.includes('?') ? '&' : '?') + '_nocache=' + Date.now(),
                useHeaders: false
            };
        }

        // 不确定的类型，默认不添加参数
        console.warn('不确定的 URL 类型，跳过缓存破坏:', url);
        return { url, useHeaders: true };
    }

    function setCacheHeaders(xhrOrOptions) {
        try {
            if (xhrOrOptions.setRequestHeader) {
                // XMLHttpRequest
                xhrOrOptions.setRequestHeader('Cache-Control', 'no-cache, no-store, must-revalidate');
                xhrOrOptions.setRequestHeader('Pragma', 'no-cache');
            } else {
                // Fetch options
                xhrOrOptions.headers = Object.assign({}, xhrOrOptions.headers, {
                    'Cache-Control': 'no-cache, no-store, must-revalidate',
                    'Pragma': 'no-cache'
                });
            }
        } catch (e) {
            // 忽略设置头的错误
        }
    }

    // 重写 XMLHttpRequest
    const OriginalXHR = window.XMLHttpRequest;
    window.XMLHttpRequest = function() {
        const xhr = new OriginalXHR();
        let _url, _method;

        const originalOpen = xhr.open;
        xhr.open = function(method, url, async, user, password) {
            _method = method.toUpperCase();
            _url = url;

            if (_method === 'GET' && isApiRequest(url)) {
                const result = safelyAddCacheBuster(url);
                url = result.url;
                if (result.useHeaders) {
                    // 标记需要设置头
                    xhr._needsCacheHeaders = true;
                }
            }

            return originalOpen.call(this, method, url, async, user, password);
        };

        const originalSend = xhr.send;
        xhr.send = function(data) {
            if (xhr._needsCacheHeaders) {
                setCacheHeaders(xhr);
            }
            return originalSend.call(this, data);
        };

        return xhr;
    };

    // 重写 Fetch
    const originalFetch = window.fetch;
    window.fetch = function(input, init) {
        let url = typeof input === 'string' ? input : input.url;
        const options = Object.assign({}, init);
        const method = (options.method || 'GET').toUpperCase();

        if (method === 'GET' && isApiRequest(url)) {
            const result = safelyAddCacheBuster(url);
            url = result.url;

            if (result.useHeaders) {
                setCacheHeaders(options);
            }

            if (typeof input === 'string') {
                input = url;
            } else {
                input = new Request(url, input);
            }
        }

        return originalFetch.call(this, input, options);
    };

    function isApiRequest(url) {
        if (isStaticResource(url)) return false;
        return cacheConfig.nonCacheableApis.some(apiPath => url.includes(apiPath));
    }

    function isStaticResource(url) {
        return cacheConfig.cacheableExtensions.some(ext =>
            url.endsWith('.' + ext) || url.includes('.' + ext + '?')
        ) || cacheConfig.cacheableDomains.some(domain =>
            url.includes(domain)
        ) || cacheConfig.cacheablePaths.some(path =>
            url.includes(path)
        );
    }

    console.log('高级缓存控制已启用：智能识别 RESTful API');
})();