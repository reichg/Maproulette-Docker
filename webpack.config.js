// if using webpack move to maproulette3 root

module.exports = {

    // define entry point --> default to src/index.js
    // entry: 'path/to/entry'

    output: {
        path: 'dist',
        filename: 'bundle.js'
    },

    watchOptions: {
        aggregateTimeout: 600,
        ignored: ['node_modules/**']
      }
}