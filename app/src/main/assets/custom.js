// very important, if you don't know what it is, don't touch it
// 非常重要，不懂代码不要动，这里可以解决80%的问题，也可以生产1000+的bug
const hookClick = (e) => {
    // 豁免规则优先级最高
    const exemptRules = [
      e.target.type === 'file',
      e.target.htmlFor === 'file-upload',
      e.target.closest(`.${styles.uploadArea}`),
      e.target.closest('[data-skip-intercept]'),
      e.target.closest('[data-upload-area]'),
      e.target.closest('[data-no-intercept]'),
    ];
  
    if (exemptRules.some(rule => rule)) {
      console.log('跳过拦截：文件上传相关操作');
      return;
    }
  
    const origin = e.target.closest('a')
    const isBaseTargetBlank = document.querySelector(
        'head base[target="_blank"]'
    )
    console.log('origin', origin, isBaseTargetBlank)
    
    if (
        (origin && origin.href && origin.target === '_blank') ||
        (origin && origin.href && isBaseTargetBlank)
    ) {
        e.preventDefault()
        console.log('handle origin', origin)
        location.href = origin.href
    } else {
        console.log('not handle origin', origin)
    }
}

window.open = function (url, target, features) {
    console.log('open', url, target, features)
    location.href = url
}

document.addEventListener('click', hookClick, { capture: true })
